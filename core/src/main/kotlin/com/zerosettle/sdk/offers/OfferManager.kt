package com.zerosettle.sdk.offers

import com.zerosettle.sdk.models.Offer
import com.zerosettle.sdk.models.UserOffer
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified offer state machine — handles **both** migrations (StoreKit→web, Play→web)
 * and upgrades (web→web, storekit→web, play→web) from the single
 * `GET /v1/iap/user-offer/` response. Mirrors iOS `ZSOfferManager` (NOT the
 * deprecated `ZSMigrationManager`).
 *
 * **Auto-bookkeeping (the modern path):** the host app calls [acceptOffer] (or
 * [dismiss]); the manager runs every state transition internally — there is no
 * `present()` / `markCheckoutSucceeded()`. The only escape hatch is [checkoutUrl]
 * for devs who present checkout through their own WebView.
 *
 * **Play→web migrations: the SDK does NOT cancel the Play subscription.** The backend
 * does that via `subscriptionsv2.cancel` after the web checkout's
 * `payment_intent.succeeded`. The manager just waits for the Play sub's auto-renew to
 * flip off ([playSubAutoRenewOff], fed by the Play reconcile) to transition
 * `ACCEPTED → COMPLETED`. For web→web upgrades there's no store cancel and no WebView —
 * `acceptOffer()` calls `POST /v1/iap/upgrade-offer/execute/` then goes straight to
 * `COMPLETED`.
 *
 * All collaborators are injected as lambdas so the manager is a pure, testable state
 * machine. `ZeroSettle.offerManager()` constructs the real one.
 */
public class OfferManager internal constructor(
    private val fetchUserOffer: suspend () -> Result<UserOffer.Response>,
    private val isDismissed: suspend () -> Boolean,
    private val persistDismissal: suspend () -> Unit,
    private val createWebCheckout: suspend (productId: String, playPurchaseToken: String?) -> Result<String>,
    private val activePlayPurchaseTokenProvider: () -> String?,
    private val trackMigrationConversion: suspend (source: String) -> Result<Unit>,
    private val playSubAutoRenewOff: () -> Boolean,
    private val launchCheckout: (checkoutUrl: String) -> Unit,
    private val onEvent: (OfferEvent) -> Unit,
    private val executeUpgradeOffer: suspend (fromProductId: String, toProductId: String) -> Result<Unit> = { _, _ -> Result.success(Unit) },
) {

    public enum class OfferState { LOADING, INELIGIBLE, ELIGIBLE, PRESENTED, ACCEPTED, COMPLETED, DISMISSED }

    /** Lightweight UI-facing events emitted alongside `ZeroSettle.events`. */
    public sealed class OfferEvent {
        public data class Shown(val productId: String) : OfferEvent()
        public data class Accepted(val productId: String) : OfferEvent()
        public data class Dismissed(val productId: String) : OfferEvent()
        public data class Completed(val productId: String) : OfferEvent()
    }

    private val _state = MutableStateFlow(OfferState.LOADING)
    public val state: StateFlow<OfferState> = _state.asStateFlow()

    private val _offerData = MutableStateFlow<Offer.OfferData?>(null)
    public val offerData: StateFlow<Offer.OfferData?> = _offerData.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    public val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _checkoutError = MutableStateFlow<ZeroSettleError?>(null)
    public val checkoutError: StateFlow<ZeroSettleError?> = _checkoutError.asStateFlow()

    /**
     * Non-null while a web checkout for an accepted offer is awaiting presentation /
     * completion. Set by [acceptOffer] (for migrations / store→web upgrades — there is
     * no checkout for web→web), cleared by [onWebCheckoutSucceeded] and
     * [cancelPendingCheckout]. The `:ui` `ZeroSettleCheckoutHost` composable observes
     * this and presents the checkout (WebView / Custom Tab); headless callers can use
     * [checkoutUrl] instead. This is the wiring for the carried-forward "actually open
     * the checkout" gap — the facade's `launchCheckout` callback still fires (it flags
     * `ZeroSettle.pendingCheckout`), this flow is the presentation handle.
     */
    private val _pendingCheckoutUrl = MutableStateFlow<String?>(null)
    public val pendingCheckoutUrl: StateFlow<String?> = _pendingCheckoutUrl.asStateFlow()

    /** Resolve eligibility from the server. Sets `LOADING → INELIGIBLE | PRESENTED`. */
    public suspend fun evaluate() {
        _isLoading.value = true
        _state.value = OfferState.LOADING
        try {
            if (isDismissed()) { _state.value = OfferState.INELIGIBLE; return }
            val resp = fetchUserOffer().getOrElse { _state.value = OfferState.INELIGIBLE; return }
            val offer = resp.offer
            if (!resp.isEligible || offer == null) { _state.value = OfferState.INELIGIBLE; return }
            _offerData.value = offer
            _state.value = OfferState.PRESENTED
            onEvent(OfferEvent.Shown(offer.productId))
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Accept the offer. Web→web upgrades: execute the server-side plan switch, then go
     * straight to `COMPLETED` (no WebView). Migrations / store→web upgrades: create the
     * web checkout (with the active Play purchase token so the backend can cancel that
     * sub), launch it, go to `ACCEPTED`.
     */
    public suspend fun acceptOffer(): Result<Unit> {
        val offer = _offerData.value ?: return Result.failure(ZeroSettleError.OfferIneligible)
        _checkoutError.value = null
        onEvent(OfferEvent.Accepted(offer.productId))

        if (offer.upgradeType == Offer.UpgradeType.WEB_TO_WEB) {
            val from = offer.fromProductId ?: offer.productId
            val to = offer.toProductId ?: offer.productId
            val r = executeUpgradeOffer(from, to)
            if (r.isFailure) {
                val err = r.exceptionOrNull()
                _checkoutError.value = err as? ZeroSettleError ?: ZeroSettleError.CheckoutFailed(err?.message ?: "unknown")
                return Result.failure(err ?: ZeroSettleError.CheckoutFailed("unknown"))
            }
            _state.value = OfferState.COMPLETED
            onEvent(OfferEvent.Completed(offer.productId))
            return Result.success(Unit)
        }

        val playToken = if (offer.sourceStorefront == Offer.SourceStorefront.PLAY_STORE) activePlayPurchaseTokenProvider() else null
        val url = createWebCheckout(offer.productId, playToken).getOrElse { err ->
            _checkoutError.value = err as? ZeroSettleError ?: ZeroSettleError.CheckoutFailed(err.message ?: "unknown")
            return Result.failure(err)
        }
        _pendingCheckoutUrl.value = url
        launchCheckout(url)
        _state.value = OfferState.ACCEPTED
        return Result.success(Unit)
    }

    /**
     * Called when the web checkout reports success (via the host's checkout callback).
     * Records the conversion (`source` = `play_store` for Play migrations, `store_kit`
     * otherwise) and — if the offer needs a store cancel — stays `ACCEPTED` until
     * [observeStoreCancellation] sees the source store's auto-renew flip off. Web→web
     * upgrades are already `COMPLETED` by then.
     */
    public suspend fun onWebCheckoutSucceeded() {
        _pendingCheckoutUrl.value = null
        val offer = _offerData.value ?: return
        val source = when (offer.sourceStorefront) {
            Offer.SourceStorefront.PLAY_STORE -> "play_store"
            Offer.SourceStorefront.STORE_KIT -> "store_kit"
        }
        trackMigrationConversion(source)
        if (!offer.needsStoreCancel) {
            _state.value = OfferState.COMPLETED
            onEvent(OfferEvent.Completed(offer.productId))
        } else {
            observeStoreCancellation()
        }
    }

    /**
     * Re-check whether the source store subscription's auto-renew has flipped off (the
     * backend cancelled it). If so, transition `ACCEPTED → COMPLETED`. Called by the
     * Play reconcile loop after each refresh, and by [onWebCheckoutSucceeded].
     */
    public fun observeStoreCancellation() {
        if (_state.value != OfferState.ACCEPTED) return
        val offer = _offerData.value ?: return
        if (offer.needsStoreCancel && playSubAutoRenewOff()) {
            _state.value = OfferState.COMPLETED
            onEvent(OfferEvent.Completed(offer.productId))
        }
    }

    public suspend fun dismiss() {
        val offer = _offerData.value
        _pendingCheckoutUrl.value = null
        persistDismissal()
        _state.value = OfferState.DISMISSED
        offer?.let { onEvent(OfferEvent.Dismissed(it.productId)) }
    }

    /**
     * The user backed out of the checkout (closed the WebView / Custom Tab) without
     * completing it. Clears [pendingCheckoutUrl]; the manager stays `ACCEPTED` so the
     * host can re-present (call [acceptOffer] again to create a fresh checkout, or
     * surface a "resume" affordance off [pendingCheckoutUrl] before it's cleared).
     */
    public fun cancelPendingCheckout() {
        _pendingCheckoutUrl.value = null
    }

    /**
     * Escape hatch for custom WebView implementations. Returns the checkout URL, or
     * `null` for web→web upgrades (no WebView needed). Does NOT change state — the
     * caller must drive `onWebCheckoutSucceeded()` / `dismiss()` itself.
     */
    public suspend fun checkoutUrl(): Result<String?> {
        val offer = _offerData.value ?: return Result.failure(ZeroSettleError.OfferIneligible)
        if (offer.upgradeType == Offer.UpgradeType.WEB_TO_WEB) return Result.success(null)
        val playToken = if (offer.sourceStorefront == Offer.SourceStorefront.PLAY_STORE) activePlayPurchaseTokenProvider() else null
        return createWebCheckout(offer.productId, playToken)
    }
}
