package com.zerosettle.sdk

import android.content.Context
import com.zerosettle.sdk.core.AppAccountToken
import com.zerosettle.sdk.core.Backend
import com.zerosettle.sdk.core.ZeroSettleEvent
import com.zerosettle.sdk.internal.IdentityStore
import com.zerosettle.sdk.internal.ZeroSettleScope
import com.zerosettle.sdk.models.Entitlement
import com.zerosettle.sdk.models.PendingAction
import com.zerosettle.sdk.models.PendingClaim
import com.zerosettle.sdk.models.Product
import com.zerosettle.sdk.models.ProductCatalog
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * SDK entry point. Mirrors iOS `ZeroSettle.shared`.
 *
 * Lifecycle:
 *
 *  1. `ZeroSettle.configure(context, config)` — at `Application.onCreate()`.
 *  2. `ZeroSettle.identify(Identity.User(id = "u1"))` — when the host app knows
 *     who the user is.
 *  3. Subsequent calls (`fetchProducts()`, `restoreEntitlements()`, `entitlements`,
 *     …) resolve against the identified user — NO `userId` parameter overloads exist.
 *
 * Current value as `StateFlow`; one-shot events as `SharedFlow` ([events]). Public
 * methods return `kotlin.Result<T>` for fallible ops — they never throw for normal
 * errors.
 */
public object ZeroSettle {

    // ─── Configuration ──────────────────────────────────────────────────────

    private val _isConfigured = MutableStateFlow(false)
    public val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    @Volatile internal var appContext: Context? = null
        private set
    @Volatile internal var config: ZeroSettleConfig? = null
        private set
    @Volatile internal var backend: Backend? = null
        private set
    @Volatile internal var identityStore: IdentityStore? = null
        private set
    @Volatile internal var playCoordinator: com.zerosettle.sdk.billing.PlayBillingCoordinator? = null
        private set
    @Volatile internal var offerDismissalStore: com.zerosettle.sdk.offers.OfferDismissalStore? = null
        private set
    @Volatile internal var entitlementPoller: com.zerosettle.sdk.entitlements.EntitlementPoller? = null
        private set

    internal val scope: ZeroSettleScope = ZeroSettleScope()
    private const val DEFAULT_BASE_URL = "https://api.zerosettle.com"

    /** The published SDK version (wired from `BuildConfig`, set by `gradle.properties`). */
    public val sdkVersion: String get() = BuildConfig.ZEROSETTLE_SDK_VERSION

    /** Call once at `Application.onCreate()`. Safe to call again to swap config. */
    public fun configure(context: Context, config: ZeroSettleConfig) {
        // Re-configure: tear down any prior Play coordinator / poller so they don't leak.
        playCoordinator?.let { coord -> runBlocking { coord.shutdown() } }
        playCoordinator = null
        entitlementPoller?.stop()
        entitlementPoller = null
        this.appContext = context.applicationContext
        this.config = config
        this.identityStore = IdentityStore(context.applicationContext)
        this.offerDismissalStore = com.zerosettle.sdk.offers.OfferDismissalStore(context.applicationContext)
        this.backend = Backend(
            baseUrl = config.baseUrlOverride ?: DEFAULT_BASE_URL,
            publishableKey = config.publishableKey,
            sdkVersion = sdkVersion,
        )
        this.entitlementPoller = com.zerosettle.sdk.entitlements.EntitlementPoller(
            backend = this.backend!!,
            userIdProvider = { activeUserId },
            onEntitlements = { ents ->
                _entitlements.value = ents
                _events.tryEmit(ZeroSettleEvent.EntitlementsRefreshed(count = ents.size))
            },
            onPendingActions = { actions -> publishPendingActionsWithEvents(actions) },
            onUnknownActionType = { config?.logger?.info("pending_actions", it) },
        )
        this.playCoordinator = buildPlayCoordinator()
        _isConfigured.value = true
    }

    /**
     * Construct a [PlayBillingCoordinator][com.zerosettle.sdk.billing.PlayBillingCoordinator]
     * bound to the current [scope] / [backend], or `null` when Play sync is disabled or
     * the SDK isn't configured. Called from [configure] and re-called from [logout] so a
     * subsequent [identify] re-enables Play sync against the fresh scope.
     */
    private fun buildPlayCoordinator(): com.zerosettle.sdk.billing.PlayBillingCoordinator? {
        val cfg = config ?: return null
        val ctx = appContext ?: return null
        val be = backend ?: return null
        if (!cfg.syncPlayPurchases) return null
        return com.zerosettle.sdk.billing.PlayBillingCoordinator(
            context = ctx,
            backend = be,
            scope = scope.scope,
            logger = cfg.logger,
            strictAck = cfg.strictAck,
            userIdProvider = { activeUserId },
            obfuscatedAccountIdProvider = {
                activeUserId?.let { uid -> AppAccountToken.derive(uid, ctx.packageName).toString() }
            },
            customerNameProvider = { customerName },
            customerEmailProvider = { customerEmail },
            onEntitlementsMayHaveChanged = {
                scope.scope.launch { restoreEntitlements() }
                entitlementPoller?.pollNow()
            },
            onPendingClaim = { pc -> _pendingClaims.value = _pendingClaims.value + pc },
            emitEvent = { e -> _events.tryEmit(e) },
        )
    }

    // ─── Identity ───────────────────────────────────────────────────────────

    private val _isBootstrapped = MutableStateFlow(false)
    public val isBootstrapped: StateFlow<Boolean> = _isBootstrapped.asStateFlow()

    @Volatile private var activeUserId: String? = null
    @Volatile private var customerName: String? = null
    @Volatile private var customerEmail: String? = null

    /**
     * Declare who the host app is acting on behalf of and bootstrap the SDK.
     *
     * Behaviour:
     *  - [Identity.User] — full bootstrap: fetch products, restore entitlements.
     *    Optional [Identity.User.name] / [Identity.User.email] are recorded as
     *    customer metadata for subsequent checkouts.
     *  - [Identity.Anonymous] — generates a stable per-install UUID, bootstraps.
     *  - [Identity.Deferred] — records intent only; suppresses the
     *    "no identity declared" warning. Returns `Result.success(null)`.
     *
     * Returns `Result.success(catalog)` on a successful bootstrap; `Result.failure`
     * with a [ZeroSettleError] otherwise.
     */
    public suspend fun identify(identity: Identity): Result<ProductCatalog?> {
        if (!_isConfigured.value) return Result.failure(ZeroSettleError.NotConfigured)
        val store = identityStore ?: return Result.failure(ZeroSettleError.NotConfigured)

        return when (identity) {
            is Identity.User -> {
                activeUserId = identity.id
                store.setActiveUserId(identity.id)
                identity.name?.let { customerName = it }
                identity.email?.let { customerEmail = it }
                if (identity.name != null || identity.email != null) {
                    store.setCustomer(name = customerName, email = customerEmail)
                }
                bootstrap(identity.id)
            }
            Identity.Anonymous -> {
                val uuid = store.anonymousUuid()
                activeUserId = uuid
                store.setActiveUserId(uuid)
                bootstrap(uuid)
            }
            Identity.Deferred -> Result.success(null)
        }
    }

    private suspend fun bootstrap(userId: String): Result<ProductCatalog?> {
        val be = backend ?: return Result.failure(ZeroSettleError.NotConfigured)
        val products = be.fetchProducts(userId).getOrElse { return Result.failure(it) }
        val entResp = be.fetchEntitlements(userId).getOrElse { return Result.failure(it) }
        _products.value = products.products
        _entitlements.value = entResp.entitlements
        _pendingClaims.value = entResp.pendingClaims
        publishPendingActionsWithEvents(parsePendingActions(entResp.pendingActions))
        _isBootstrapped.value = true
        _events.tryEmit(ZeroSettleEvent.EntitlementsRefreshed(count = entResp.entitlements.size))
        playCoordinator?.start()
        entitlementPoller?.start(scope.scope)
        return Result.success(products)
    }

    /** Update customer metadata for subsequent checkouts / syncs. */
    public fun setCustomer(name: String? = null, email: String? = null) {
        if (name != null) customerName = name
        if (email != null) customerEmail = email
        identityStore?.let { store ->
            runBlocking { store.setCustomer(name = customerName, email = customerEmail) }
        }
    }

    /** Clear identity, customer metadata, sync queue. Cancels in-flight background work. */
    public fun logout() {
        // Shut the Play coordinator down BEFORE cancelling the scope so its shutdown
        // work doesn't run against a cancelled scope; recreate it so a subsequent
        // identify() re-enables Play sync.
        entitlementPoller?.stop()
        playCoordinator?.let { coord -> runBlocking { coord.shutdown() } }
        scope.reset()
        activeUserId = null
        customerName = null
        customerEmail = null
        _isBootstrapped.value = false
        _products.value = emptyList()
        _entitlements.value = emptyList()
        _pendingClaims.value = emptyList()
        _pendingActions.value = emptyList()
        identityStore?.let { store -> runBlocking { store.clear() } }
        playCoordinator = buildPlayCoordinator()
    }

    // ─── Observables ────────────────────────────────────────────────────────

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    public val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _entitlements = MutableStateFlow<List<Entitlement>>(emptyList())
    public val entitlements: StateFlow<List<Entitlement>> = _entitlements.asStateFlow()

    public val activeEntitlements: List<Entitlement> get() = _entitlements.value.filter { it.isActive }

    private val _pendingClaims = MutableStateFlow<List<PendingClaim>>(emptyList())
    public val pendingClaims: StateFlow<List<PendingClaim>> = _pendingClaims.asStateFlow()

    private val _pendingActions = MutableStateFlow<List<PendingAction>>(emptyList())
    public val pendingActions: StateFlow<List<PendingAction>> = _pendingActions.asStateFlow()
    internal fun publishPendingActions(value: List<PendingAction>) { _pendingActions.value = value }

    /** Decode the raw `pending_actions[]` JSON list; unknown / malformed rows are logged and dropped. */
    private fun parsePendingActions(raw: List<kotlinx.serialization.json.JsonObject>): List<PendingAction> =
        com.zerosettle.sdk.entitlements.PendingActionParser.parse(raw) { config?.logger?.info("pending_actions", it) }

    /**
     * Replace [pendingActions] with [actions] and emit a [ZeroSettleEvent.PendingActionShown]
     * for any action (keyed by `transactionId` + variant) that wasn't already showing — so a
     * banner only "appears" once per surfacing, not on every poll.
     */
    private fun publishPendingActionsWithEvents(actions: List<PendingAction>) {
        fun key(a: PendingAction): String = when (a) {
            is PendingAction.MigrationCompletedInfo -> "migration_completed_info:${a.transactionId}"
            is PendingAction.ManualPlayCancel -> "manual_play_cancel:${a.transactionId}"
        }
        val before = _pendingActions.value.map(::key).toSet()
        _pendingActions.value = actions
        actions.filter { key(it) !in before }.forEach { a ->
            _events.tryEmit(
                ZeroSettleEvent.PendingActionShown(
                    actionType = when (a) {
                        is PendingAction.MigrationCompletedInfo -> "migration_completed_info"
                        is PendingAction.ManualPlayCancel -> "manual_play_cancel"
                    },
                ),
            )
        }
    }

    private val _pendingCheckout = MutableStateFlow(false)
    public val pendingCheckout: StateFlow<Boolean> = _pendingCheckout.asStateFlow()
    internal fun setPendingCheckout(value: Boolean) { _pendingCheckout.value = value }

    private val _events = MutableSharedFlow<ZeroSettleEvent>(extraBufferCapacity = 32)
    public val events: SharedFlow<ZeroSettleEvent> = _events.asSharedFlow()
    internal fun emitEvent(e: ZeroSettleEvent) { _events.tryEmit(e) }

    // ─── Convenience accessors ──────────────────────────────────────────────

    public fun product(referenceId: String): Product? = _products.value.firstOrNull { it.id == referenceId }
    public fun hasActiveEntitlement(productId: String): Boolean =
        _entitlements.value.any { it.productId == productId && it.isActive }

    internal fun requireUserId(): String = activeUserId ?: throw ZeroSettleError.UserNotIdentified
    internal fun currentUserIdOrNull(): String? = activeUserId
    internal fun currentCustomerName(): String? = customerName
    internal fun currentCustomerEmail(): String? = customerEmail

    public fun recommendedAppAccountToken(): UUID {
        val uid = requireUserId()
        val pkg = appContext?.packageName ?: throw ZeroSettleError.NotConfigured
        return AppAccountToken.derive(userId = uid, packageName = pkg)
    }

    // ─── Public catalog / entitlement helpers ───────────────────────────────

    /**
     * Re-fetch the canonical product catalog. Updates [products] on success.
     * Returns `Result.failure(ZeroSettleError.UserNotIdentified)` if [identify]
     * hasn't been called.
     */
    public suspend fun fetchProducts(): Result<ProductCatalog> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        val be = backend ?: return Result.failure(ZeroSettleError.NotConfigured)
        return be.fetchProducts(uid).onSuccess { _products.value = it.products }
    }

    /**
     * Re-fetch active entitlements. Updates [entitlements] / [pendingClaims] and
     * emits [ZeroSettleEvent.EntitlementsRefreshed] on success. Returns the active
     * entitlement list, or `Result.failure(ZeroSettleError.UserNotIdentified)`.
     */
    public suspend fun restoreEntitlements(): Result<List<Entitlement>> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        val be = backend ?: return Result.failure(ZeroSettleError.NotConfigured)
        return be.fetchEntitlements(uid).map { resp ->
            _entitlements.value = resp.entitlements
            _pendingClaims.value = resp.pendingClaims
            publishPendingActionsWithEvents(parsePendingActions(resp.pendingActions))
            _events.tryEmit(ZeroSettleEvent.EntitlementsRefreshed(count = resp.entitlements.size))
            resp.entitlements
        }
    }

    /**
     * Fetch the identified user's full transaction history as raw JSON
     * (`GET /v1/iap/transaction-history/`). The typed model lands in a later phase.
     */
    public suspend fun fetchTransactionHistory(): Result<String> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        val be = backend ?: return Result.failure(ZeroSettleError.NotConfigured)
        return be.fetchTransactionHistory(uid)
    }

    // ─── Web checkout (Stripe) ─────────────────────────────────────────────

    /**
     * Open the Stripe web checkout for [productId]. Returns the `checkout_url` on
     * success (the SDK launches it in a Custom Tab and also returns it for callers
     * who want their own presentation). Sets [pendingCheckout] = true; cleared by
     * [completeWebCheckout] when the checkout page redirects back.
     *
     * Mirrors iOS `ZeroSettle.shared.purchase(_:)` — web checkout, NOT native Play.
     */
    public suspend fun purchase(activity: android.app.Activity, productId: String): Result<String> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        val be = backend ?: return Result.failure(ZeroSettleError.NotConfigured)
        return be.createWebCheckout(
            userId = uid, productId = productId, playPurchaseToken = null,
            customerName = customerName, customerEmail = customerEmail,
        ).map { resp ->
            _pendingCheckout.value = true
            when (resp.checkoutPresentation) {
                com.zerosettle.sdk.checkout.CheckoutPresentation.BROWSER ->
                    com.zerosettle.sdk.checkout.WebCheckoutFlow.launchExternalBrowser(activity, resp.checkoutUrl)
                else ->
                    com.zerosettle.sdk.checkout.WebCheckoutFlow.launchCustomTab(activity, resp.checkoutUrl)
            }
            resp.checkoutUrl
        }
    }

    /**
     * Feed the `zerosettle://checkout/return…` deep link back into the SDK. The host
     * app calls this from the Activity that received the redirect intent. Clears
     * [pendingCheckout]; on success, refreshes entitlements and emits a
     * [ZeroSettleEvent.PurchaseSucceeded].
     */
    public suspend fun completeWebCheckout(callbackUrl: String): Result<Unit> {
        val parsed = com.zerosettle.sdk.checkout.WebCheckoutFlow.parseCallback(callbackUrl)
            ?: return Result.failure(ZeroSettleError.CheckoutFailed("not_a_callback_url"))
        _pendingCheckout.value = false
        return when (parsed) {
            is com.zerosettle.sdk.checkout.WebCheckoutFlow.CallbackResult.Succeeded -> {
                restoreEntitlements().map {
                    _events.tryEmit(ZeroSettleEvent.PurchaseSucceeded(productId = "", transactionId = parsed.transactionId ?: ""))
                }
            }
            com.zerosettle.sdk.checkout.WebCheckoutFlow.CallbackResult.Cancelled ->
                Result.failure(ZeroSettleError.PurchaseCancelled)
            is com.zerosettle.sdk.checkout.WebCheckoutFlow.CallbackResult.Failed -> {
                _events.tryEmit(ZeroSettleEvent.PurchaseFailed(productId = "", reason = parsed.reason))
                Result.failure(ZeroSettleError.CheckoutFailed(parsed.reason))
            }
        }
    }

    // ─── Native Play Billing ───────────────────────────────────────────────

    /**
     * Native Play Billing purchase for [productId]. Originates via `BillingClient`,
     * forwards the resulting purchase to `POST /v1/iap/play-store-transactions/`, and
     * acknowledges only after the backend confirms (3-day-window rule — see
     * [com.zerosettle.sdk.billing.PurchaseSyncProcessor]). Mirrors iOS `purchaseViaStoreKit()`.
     *
     * Requires `ZeroSettleConfig.syncPlayPurchases = true`.
     */
    public suspend fun purchaseViaPlayBilling(activity: android.app.Activity, productId: String): Result<Unit> {
        if (currentUserIdOrNull() == null) return Result.failure(ZeroSettleError.UserNotIdentified)
        val coord = playCoordinator ?: return Result.failure(ZeroSettleError.NotConfigured)
        val product = product(productId) ?: return Result.failure(ZeroSettleError.ProductNotFound(productId))
        return coord.purchaseViaPlayBilling(activity, product)
    }

    // ─── Unified offers ────────────────────────────────────────────────────

    /**
     * Build an [OfferManager][com.zerosettle.sdk.offers.OfferManager] for the
     * currently-identified user. Resolves eligibility from `GET /v1/iap/user-offer/`.
     * Mirrors iOS `ZeroSettle.shared.offerManager(stripeCustomerId:)`.
     *
     * @throws ZeroSettleError.UserNotIdentified if [identify] hasn't run.
     * @throws ZeroSettleError.NotConfigured if [configure] hasn't run.
     */
    public fun offerManager(stripeCustomerId: String? = null): com.zerosettle.sdk.offers.OfferManager {
        val uid = currentUserIdOrNull() ?: throw ZeroSettleError.UserNotIdentified
        val be = backend ?: throw ZeroSettleError.NotConfigured
        val dismissals = offerDismissalStore ?: throw ZeroSettleError.NotConfigured
        return com.zerosettle.sdk.offers.OfferManager(
            fetchUserOffer = { be.fetchUserOffer(uid) },
            isDismissed = { dismissals.isDismissed(uid) },
            persistDismissal = { dismissals.dismiss(uid) },
            createWebCheckout = { productId, playToken ->
                be.createWebCheckout(uid, productId, playToken, customerName, customerEmail).map { it.checkoutUrl }
            },
            activePlayPurchaseTokenProvider = {
                _entitlements.value.firstOrNull {
                    it.source == com.zerosettle.sdk.models.EntitlementSource.PLAY_STORE && it.isActive
                }?.playPurchaseToken
            },
            trackMigrationConversion = { source -> be.trackMigrationConversion(uid, source) },
            playSubAutoRenewOff = {
                val ent = _entitlements.value.firstOrNull { it.source == com.zerosettle.sdk.models.EntitlementSource.PLAY_STORE }
                ent != null && !ent.willRenew
            },
            // The :ui module's offer-tip / checkout-sheet composables drive presentation
            // (an Activity is needed for a Custom Tab); headless callers use
            // OfferManager.checkoutUrl() and present it themselves. We just flag that a
            // checkout is in flight so `pendingCheckout` reflects reality.
            launchCheckout = { _ -> _pendingCheckout.value = true },
            onEvent = { e ->
                when (e) {
                    is com.zerosettle.sdk.offers.OfferManager.OfferEvent.Shown -> _events.tryEmit(ZeroSettleEvent.OfferShown(e.productId))
                    is com.zerosettle.sdk.offers.OfferManager.OfferEvent.Accepted -> _events.tryEmit(ZeroSettleEvent.OfferAccepted(e.productId))
                    is com.zerosettle.sdk.offers.OfferManager.OfferEvent.Dismissed -> _events.tryEmit(ZeroSettleEvent.OfferDismissed(e.productId))
                    is com.zerosettle.sdk.offers.OfferManager.OfferEvent.Completed -> _events.tryEmit(ZeroSettleEvent.MigrationCompleted(e.productId))
                }
            },
            executeUpgradeOffer = { from, to -> be.executeUpgradeOffer(uid, from, to).map { } },
        )
    }

    public suspend fun fetchUserOffer(): Result<com.zerosettle.sdk.models.UserOffer.Response> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        return (backend ?: return Result.failure(ZeroSettleError.NotConfigured)).fetchUserOffer(uid)
    }

    public suspend fun fetchCancelFlowConfig(): Result<com.zerosettle.sdk.models.CancelFlow.Config> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        return (backend ?: return Result.failure(ZeroSettleError.NotConfigured)).fetchCancelFlowConfig(uid)
    }

    public suspend fun fetchUpgradeOfferConfig(productId: String? = null): Result<com.zerosettle.sdk.models.UpgradeOffer.Config> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        return (backend ?: return Result.failure(ZeroSettleError.NotConfigured)).fetchUpgradeOfferConfig(uid, productId)
    }

    public suspend fun acceptSaveOffer(productId: String): Result<com.zerosettle.sdk.models.CancelFlow.SaveOfferResult> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        return (backend ?: return Result.failure(ZeroSettleError.NotConfigured)).acceptSaveOffer(uid, productId)
    }

    /**
     * Execute a web→web subscription plan switch (`POST /v1/iap/upgrade-offer/execute/`).
     * Refreshes entitlements on success. Returns the new product id.
     */
    public suspend fun executeUpgradeOffer(fromProductId: String, toProductId: String): Result<String> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        return (backend ?: return Result.failure(ZeroSettleError.NotConfigured))
            .executeUpgradeOffer(uid, fromProductId, toProductId)
            .map { it.newProductId }
            .onSuccess { scope.scope.launch { restoreEntitlements() }; entitlementPoller?.pollNow() }
    }

    /**
     * Record that a migration offer converted (`POST /v1/iap/migration-converted/`).
     * [source] is `[Offer.SourceStorefront.PLAY_STORE]` for a Play→web migration,
     * `[Offer.SourceStorefront.STORE_KIT]` for a StoreKit→web one. [OfferManager]
     * calls this automatically; exposed here for headless / custom-WebView callers.
     */
    public suspend fun trackMigrationConversion(source: com.zerosettle.sdk.models.Offer.SourceStorefront): Result<Unit> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        val wire = when (source) {
            com.zerosettle.sdk.models.Offer.SourceStorefront.PLAY_STORE -> "play_store"
            com.zerosettle.sdk.models.Offer.SourceStorefront.STORE_KIT -> "store_kit"
        }
        return (backend ?: return Result.failure(ZeroSettleError.NotConfigured)).trackMigrationConversion(uid, wire)
    }

    // ─── Subscription management ───────────────────────────────────────────

    public suspend fun cancelSubscription(productId: String, immediate: Boolean = false): Result<Unit> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        return (backend ?: return Result.failure(ZeroSettleError.NotConfigured)).cancelSubscription(uid, productId, immediate)
            .onSuccess { scope.scope.launch { restoreEntitlements() }; entitlementPoller?.pollNow() }
    }

    public suspend fun pauseSubscription(productId: String, pauseDurationDays: Int?): Result<String?> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        return (backend ?: return Result.failure(ZeroSettleError.NotConfigured)).pauseSubscription(uid, productId, pauseDurationDays)
            .map { it.resumesAt }.onSuccess { scope.scope.launch { restoreEntitlements() }; entitlementPoller?.pollNow() }
    }

    public suspend fun resumeSubscription(productId: String): Result<Unit> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        return (backend ?: return Result.failure(ZeroSettleError.NotConfigured)).resumeSubscription(uid, productId)
            .onSuccess { scope.scope.launch { restoreEntitlements() }; entitlementPoller?.pollNow() }
    }

    // ─── Cross-account claim ───────────────────────────────────────────────

    /**
     * Move a Play subscription / non-consumable from its current ZeroSettle owner to
     * the currently-identified user. Destructive — resolves a [PendingClaim]. Posts to
     * `POST /v1/iap/claim-entitlement/`. Never auto-invoked; the host app calls this
     * explicitly in response to a `pendingClaims` entry.
     */
    public suspend fun transferPlayOwnershipToCurrentUser(productId: String, originalTransactionId: String): Result<Unit> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        return (backend ?: return Result.failure(ZeroSettleError.NotConfigured))
            .claimEntitlement(uid, productId, originalTransactionId)
            .onSuccess {
                _pendingClaims.value = _pendingClaims.value.filterNot {
                    it.productId == productId && it.originalTransactionId == originalTransactionId
                }
                scope.scope.launch { restoreEntitlements() }
                entitlementPoller?.pollNow()
            }
    }

    // ─── Pending actions (chunk 3 surface) ─────────────────────────────────

    /**
     * Dismiss a [PendingAction] (typically a [PendingAction.MigrationCompletedInfo]
     * one-time info banner). Posts to
     * `POST /v1/iap/migration-actions/<transactionId>/dismiss/` with the identified
     * user id and the action-type discriminator
     * (`"info_banner_dismissed"` / `"manual_play_cancel_completed"`), then optimistically
     * removes the action from [pendingActions] so it doesn't re-surface. For a
     * [PendingAction.ManualPlayCancel], dismissal just hides the banner — the action
     * also naturally disappears once Play sends the cancel RTDN and the backend reconciles.
     */
    public suspend fun dismissPendingAction(action: PendingAction): Result<Unit> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        val be = backend ?: return Result.failure(ZeroSettleError.NotConfigured)
        val actionType = when (action) {
            is PendingAction.MigrationCompletedInfo -> "info_banner_dismissed"
            is PendingAction.ManualPlayCancel -> "manual_play_cancel_completed"
        }
        return be.dismissMigrationAction(transactionId = action.transactionId, userId = uid, actionType = actionType)
            .onSuccess {
                _pendingActions.value = _pendingActions.value.filterNot {
                    it::class == action::class && it.transactionId == action.transactionId
                }
            }
    }

    // ─── Test surface ───────────────────────────────────────────────────────

    /** Test-only: reset all state. */
    internal fun resetForTesting() {
        scope.reset()
        _isConfigured.value = false
        _isBootstrapped.value = false
        _products.value = emptyList()
        _entitlements.value = emptyList()
        _pendingClaims.value = emptyList()
        _pendingActions.value = emptyList()
        _pendingCheckout.value = false
        entitlementPoller?.stop()
        entitlementPoller = null
        playCoordinator?.let { coord -> runBlocking { coord.shutdown() } }
        playCoordinator = null
        offerDismissalStore = null
        appContext = null
        config = null
        backend = null
        identityStore = null
        activeUserId = null
        customerName = null
        customerEmail = null
    }

    internal fun activeUserIdForTesting(): String? = activeUserId
    internal fun customerForTesting(): Pair<String?, String?> = customerName to customerEmail

    /** Test-only: the Play sync queue owned by the coordinator (requires `syncPlayPurchases = true`). */
    internal fun playSyncQueueForTesting(): com.zerosettle.sdk.billing.PlaySyncQueue = playCoordinator!!.queue

    /**
     * Debug only: the number of Play purchases currently queued for retry by the
     * persistent sync queue. Returns 0 when Play sync is disabled or no coordinator
     * exists. Public so a separate Gradle module (e.g. the `:sample` debug screen) can
     * inspect internal state; not part of the supported product surface.
     */
    public suspend fun playSyncQueueDepthForDebug(): Int = playCoordinator?.queue?.pending()?.size ?: 0
}
