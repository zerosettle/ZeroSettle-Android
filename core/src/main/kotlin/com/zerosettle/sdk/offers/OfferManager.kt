package com.zerosettle.sdk.offers

import android.app.Activity
import com.zerosettle.sdk.core.ZeroSettleLogger
import com.zerosettle.sdk.models.UserOffer
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified offer state machine — handles **both** the StoreKit→web migration and
 * upgrades (web→web, storekit→web) from the single `GET /v1/iap/user-offer/`
 * response ([UserOffer.OfferData]). Mirrors iOS `ZSOfferManager` (NOT the
 * deprecated `ZSMigrationManager`).
 *
 * **Auto-bookkeeping (the modern path):** the host app calls [acceptOffer] (or
 * [dismiss]); the manager runs every state transition internally — there is no
 * `present()` / `markCheckoutSucceeded()`. The only escape hatch is [checkoutUrl]
 * for devs who present checkout through their own WebView.
 *
 * **Play→web migrations: the SDK does NOT cancel the Play subscription.** The
 * backend does that via `subscriptionsv2.cancel` after the web checkout's
 * `payment_intent.succeeded`. The manager just waits for the Play sub's auto-renew
 * to flip off ([playSubAutoRenewOff], read from the current Play entitlement's
 * `willRenew` flag) to transition
 * `ACCEPTED → COMPLETED`. For `upgrade_web_to_web` there's no store cancel and no
 * WebView — `acceptOffer()` calls `POST /v1/iap/upgrade-offer/execute/` then goes
 * straight to `COMPLETED`.
 *
 * All collaborators are injected as lambdas so the manager is a pure, testable
 * state machine. `ZeroSettle.offerManager()` constructs the real one.
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
    /** Reports a declined/dismissed upgrade offer to `POST /v1/iap/upgrade-offer/respond/`. No-op for migrations. */
    private val respondUpgradeOffer: suspend (fromProductId: String, toProductId: String, outcome: String) -> Result<Unit> = { _, _, _ -> Result.success(Unit) },
    /**
     * Returns `true` when Google's External Content Link (ECL) billing program is
     * available on the current device/account. Used to gate [UserOffer.ActionType.MIGRATE_PLAY_TO_WEB]
     * offers — if ECL is unavailable the offer is suppressed (never shown). Injected as a
     * testable seam; the real implementation delegates to [com.zerosettle.sdk.billing.ExternalContentLinkClient.isAvailable].
     */
    private val isEclAvailable: suspend () -> Boolean = { true },
    /**
     * Executes the Switch & Save (Play→web) flow for a [UserOffer.ActionType.MIGRATE_PLAY_TO_WEB]
     * offer. Called from [acceptOffer] when the offer requires an [Activity] anchor for the ECL
     * disclosure dialog. Injected as a testable seam; the real implementation is
     * [com.zerosettle.sdk.ZeroSettle.launchSwitchAndSave].
     */
    private val launchSwitchAndSave: suspend (Activity) -> Result<Unit> = { Result.failure(ZeroSettleError.SwitchAndSaveUnavailable) },
    private val logger: ZeroSettleLogger? = null,
) {

    public enum class OfferState { LOADING, INELIGIBLE, ELIGIBLE, PRESENTED, ACCEPTED, COMPLETED, DISMISSED, ERROR }

    /** Lightweight UI-facing events emitted alongside `ZeroSettle.events`. */
    public sealed class OfferEvent {
        public data class Shown(val productId: String) : OfferEvent()
        public data class Accepted(val productId: String) : OfferEvent()
        public data class Dismissed(val productId: String) : OfferEvent()
        public data class Completed(val productId: String) : OfferEvent()
        /** The user-offer call failed (transport / decode error) — distinct from a quiet "ineligible". */
        public data class EvaluationFailed(val error: ZeroSettleError) : OfferEvent()
    }

    private val _state = MutableStateFlow(OfferState.LOADING)
    public val state: StateFlow<OfferState> = _state.asStateFlow()

    private val _offerData = MutableStateFlow<UserOffer.OfferData?>(null)
    public val offerData: StateFlow<UserOffer.OfferData?> = _offerData.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    public val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _checkoutError = MutableStateFlow<ZeroSettleError?>(null)
    public val checkoutError: StateFlow<ZeroSettleError?> = _checkoutError.asStateFlow()

    /**
     * Non-null while a web checkout for an accepted offer is awaiting presentation /
     * completion. Set by [acceptOffer] (for the migration / store→web upgrade — there
     * is no checkout for web→web), cleared by [onWebCheckoutSucceeded] and
     * [cancelPendingCheckout].
     */
    private val _pendingCheckoutUrl = MutableStateFlow<String?>(null)
    public val pendingCheckoutUrl: StateFlow<String?> = _pendingCheckoutUrl.asStateFlow()

    /**
     * Resolve eligibility from the server.
     *
     * `LOADING → PRESENTED` when an eligible offer comes back. `LOADING → INELIGIBLE`
     * for a *legitimate* "no offer" (dismissed locally, server says not eligible, or
     * `action_type == no_action`). `LOADING → ERROR` when the user-offer call itself
     * fails (network down, response failed to decode) — that is NOT a quiet
     * ineligibility; it's logged and surfaced via [checkoutError] + an
     * [OfferEvent.EvaluationFailed] event.
     */
    public suspend fun evaluate() {
        _isLoading.value = true
        _state.value = OfferState.LOADING
        _checkoutError.value = null
        try {
            if (isDismissed()) {
                logger?.info("OfferManager", "evaluate → INELIGIBLE: offer was dismissed locally by this user")
                _state.value = OfferState.INELIGIBLE; return
            }
            val resp = fetchUserOffer().getOrElse { err ->
                val zsErr = err as? ZeroSettleError ?: ZeroSettleError.NetworkError(err)
                logger?.error("OfferManager", "user-offer fetch/decode failed: ${zsErr.message}", err)
                _checkoutError.value = zsErr
                _state.value = OfferState.ERROR
                onEvent(OfferEvent.EvaluationFailed(zsErr))
                return
            }
            val offer = resp.eligibleOffer
            if (offer == null) {
                logger?.info(
                    "OfferManager",
                    "evaluate → INELIGIBLE: backend returned no eligible offer " +
                        "(is_eligible=false or action_type=no_action)",
                )
                _state.value = OfferState.INELIGIBLE; return
            }
            // MIGRATE_PLAY_TO_WEB requires the ECL billing program to be available on this
            // device. If unavailable, suppress the offer (INELIGIBLE) so non-enrolled users
            // never see a dead CTA.
            if (offer.actionType == UserOffer.ActionType.MIGRATE_PLAY_TO_WEB && !isEclAvailable()) {
                logger?.warn(
                    "OfferManager",
                    "evaluate → INELIGIBLE: MIGRATE_PLAY_TO_WEB (Switch & Save) offer for " +
                        "${offer.checkoutProductId} suppressed — ECL unavailable on this device/account. " +
                        "Set ZeroSettle.eclAvailabilityOverride = true to test without an ECL-enrolled device.",
                )
                _state.value = OfferState.INELIGIBLE; return
            }
            _offerData.value = offer
            _state.value = OfferState.PRESENTED
            logger?.info("OfferManager", "evaluate → PRESENTED: ${offer.actionType} offer for ${offer.checkoutProductId}")
            onEvent(OfferEvent.Shown(offer.checkoutProductId))
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Accept the offer. `upgrade_web_to_web`: execute the server-side plan switch,
     * then go straight to `COMPLETED` (no WebView). Migration / `upgrade_storekit_to_web`:
     * create the web checkout (with the active Play purchase token, when the source is
     * Play, so the backend can cancel that sub), launch it, go to `ACCEPTED`.
     *
     * **`MIGRATE_PLAY_TO_WEB` is rejected here.** A Play→web migration must run through
     * Google's External Content Link flow ([acceptOffer] with an `Activity`) — it must
     * NEVER fall back to the in-app WebView checkout (a Google Play policy violation).
     * The no-arg path returns [ZeroSettleError.SwitchAndSaveRequiresActivity] without
     * touching `createWebCheckout` / `_pendingCheckoutUrl`.
     */
    public suspend fun acceptOffer(): Result<Unit> {
        val offer = _offerData.value ?: return Result.failure(ZeroSettleError.OfferIneligible)
        if (offer.actionType == UserOffer.ActionType.MIGRATE_PLAY_TO_WEB) {
            return Result.failure(ZeroSettleError.SwitchAndSaveRequiresActivity)
        }
        _checkoutError.value = null
        onEvent(OfferEvent.Accepted(offer.checkoutProductId))

        if (offer.isHeadlessUpgrade) {
            val from = offer.fromProductId ?: offer.checkoutProductId
            val to = offer.checkoutProductId
            val r = executeUpgradeOffer(from, to)
            if (r.isFailure) {
                val err = r.exceptionOrNull()
                _checkoutError.value = err as? ZeroSettleError ?: ZeroSettleError.CheckoutFailed(err?.message ?: "unknown")
                return Result.failure(err ?: ZeroSettleError.CheckoutFailed("unknown"))
            }
            _state.value = OfferState.COMPLETED
            onEvent(OfferEvent.Completed(offer.checkoutProductId))
            return Result.success(Unit)
        }

        val playToken = if (offer.source == UserOffer.SourceStorefront.PLAY_STORE) activePlayPurchaseTokenProvider() else null
        val url = createWebCheckout(offer.checkoutProductId, playToken).getOrElse { err ->
            _checkoutError.value = err as? ZeroSettleError ?: ZeroSettleError.CheckoutFailed(err.message ?: "unknown")
            return Result.failure(err)
        }
        _pendingCheckoutUrl.value = url
        launchCheckout(url)
        _state.value = OfferState.ACCEPTED
        return Result.success(Unit)
    }

    /**
     * Accept a [UserOffer.ActionType.MIGRATE_PLAY_TO_WEB] offer. Routes to
     * [launchSwitchAndSave] (the ECL billing program) rather than the normal
     * web-checkout path. On success, state transitions to `ACCEPTED` — the actual
     * checkout completion happens asynchronously (backend webhook → entitlement event).
     *
     * For all other offer types, delegates to the no-arg [acceptOffer].
     *
     * @param activity The foreground [Activity] required to anchor the ECL disclosure dialog.
     */
    public suspend fun acceptOffer(activity: Activity): Result<Unit> {
        val offer = _offerData.value ?: return Result.failure(ZeroSettleError.OfferIneligible)
        if (offer.actionType != UserOffer.ActionType.MIGRATE_PLAY_TO_WEB) {
            return acceptOffer()
        }

        _checkoutError.value = null
        onEvent(OfferEvent.Accepted(offer.checkoutProductId))

        val result = launchSwitchAndSave(activity)
        if (result.isFailure) {
            val err = result.exceptionOrNull()
            _checkoutError.value = err as? ZeroSettleError ?: ZeroSettleError.CheckoutFailed(err?.message ?: "unknown")
            return Result.failure(err ?: ZeroSettleError.CheckoutFailed("unknown"))
        }
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
        when (offer.source) {
            UserOffer.SourceStorefront.PLAY_STORE -> trackMigrationConversion("play_store")
            UserOffer.SourceStorefront.STORE_KIT -> trackMigrationConversion("store_kit")
            null -> { /* not a migration — nothing to track */ }
        }
        if (!offer.needsStoreCancel) {
            _state.value = OfferState.COMPLETED
            onEvent(OfferEvent.Completed(offer.checkoutProductId))
        } else {
            observeStoreCancellation()
        }
    }

    /**
     * Overload accepting an optional `transactionId` for Dart-wire-shape
     * parity with iOS (which threads the id through `markCheckoutSucceeded`).
     * The id is currently unused — the SDK's checkout-success path
     * reconciles against backend state, not a client-supplied id — but the
     * overload exists so the Flutter plugin can forward the arg without
     * dropping it; future SDK releases may use the id without requiring a
     * plugin change.
     */
    public suspend fun onWebCheckoutSucceeded(transactionId: String?) {
        @Suppress("UNUSED_PARAMETER") val ignored = transactionId
        onWebCheckoutSucceeded()
    }

    /**
     * Re-check whether the source store subscription's auto-renew has flipped off (the
     * backend cancelled it). If so, transition `ACCEPTED → COMPLETED`. Called by
     * [onWebCheckoutSucceeded].
     */
    public fun observeStoreCancellation() {
        if (_state.value != OfferState.ACCEPTED) return
        val offer = _offerData.value ?: return
        if (offer.needsStoreCancel && playSubAutoRenewOff()) {
            _state.value = OfferState.COMPLETED
            onEvent(OfferEvent.Completed(offer.checkoutProductId))
        }
    }

    public suspend fun dismiss() {
        val offer = _offerData.value
        _pendingCheckoutUrl.value = null
        // Report the dismissal for upgrade offers so analytics stays accurate. Migrations
        // record dismissal locally only (no server endpoint for migration-tip dismissals).
        if (offer != null && offer.actionType != UserOffer.ActionType.NO_ACTION &&
            (offer.actionType == UserOffer.ActionType.UPGRADE_WEB_TO_WEB || offer.actionType == UserOffer.ActionType.UPGRADE_STOREKIT_TO_WEB)
        ) {
            val from = offer.fromProductId ?: offer.checkoutProductId
            respondUpgradeOffer(from, offer.checkoutProductId, "dismissed")
        }
        persistDismissal()
        _state.value = OfferState.DISMISSED
        offer?.let { onEvent(OfferEvent.Dismissed(it.checkoutProductId)) }
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
     * `null` for `upgrade_web_to_web` (no WebView needed). Does NOT change state — the
     * caller must drive `onWebCheckoutSucceeded()` / `dismiss()` itself.
     *
     * **`MIGRATE_PLAY_TO_WEB` is rejected here.** A Play→web migration has no
     * WebView-checkout URL — it must run through Google's External Content Link flow
     * ([acceptOffer] with an `Activity`). Returns
     * [ZeroSettleError.SwitchAndSaveRequiresActivity] rather than minting a Stripe
     * checkout URL (which would be a Google Play policy violation).
     */
    public suspend fun checkoutUrl(): Result<String?> {
        val offer = _offerData.value ?: return Result.failure(ZeroSettleError.OfferIneligible)
        if (offer.actionType == UserOffer.ActionType.MIGRATE_PLAY_TO_WEB) {
            return Result.failure(ZeroSettleError.SwitchAndSaveRequiresActivity)
        }
        if (offer.isHeadlessUpgrade) return Result.success(null)
        val playToken = if (offer.source == UserOffer.SourceStorefront.PLAY_STORE) activePlayPurchaseTokenProvider() else null
        return createWebCheckout(offer.checkoutProductId, playToken)
    }
}
