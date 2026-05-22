package com.zerosettle.sdk

import android.content.Context
import com.zerosettle.sdk.billing.ExternalContentLinkClient
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /**
     * Testing override for the Switch & Save (Play→web) offer's ECL gate.
     *
     * `MIGRATE_PLAY_TO_WEB` offers are only surfaced when Google's External
     * Content Link program reports available on the device — [com.zerosettle.sdk.offers.OfferManager]
     * suppresses them otherwise. ECL is limited-availability, so on dev/QA
     * devices not enrolled in the program the Switch & Save tip never appears.
     *
     * Set `true` to force the ECL availability check to pass (or `false` to
     * force-fail it) so developers can exercise the Switch & Save UI without
     * an ECL-enrolled device/account. Leave `null` in production — when
     * `null`, the real Play Billing query runs.
     */
    @Volatile public var eclAvailabilityOverride: Boolean? = null

    @Volatile internal var identityStore: IdentityStore? = null
        private set
    @Volatile internal var playCoordinator: com.zerosettle.sdk.billing.PlayBillingCoordinator? = null
        private set
    @Volatile internal var offerDismissalStore: com.zerosettle.sdk.offers.OfferDismissalStore? = null
        private set
    @Volatile internal var entitlementPoller: com.zerosettle.sdk.entitlements.EntitlementPoller? = null
        private set
    @Volatile internal var ucbConfigRepository: com.zerosettle.sdk.billing.UcbConfigRepository? = null
        private set

    internal val scope: ZeroSettleScope = ZeroSettleScope()

    /**
     * Process-lifetime IO scope. Survives [logout] (which cancels [scope] via
     * [ZeroSettleScope.reset]) so that fire-and-forget persistence — DataStore
     * writes from [setCustomer], [configure], [logout] — completes even when
     * the caller-visible scope has been torn down.
     */
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val DEFAULT_BASE_URL = "https://api.zerosettle.io"

    /** The published SDK version (wired from `BuildConfig`, set by `gradle.properties`). */
    public val sdkVersion: String get() = BuildConfig.ZEROSETTLE_SDK_VERSION

    /**
     * Call once at `Application.onCreate()`. Safe to call again to swap config.
     *
     * Synchronously installs the new configuration (context / store / backend
     * / poller / coordinator) so callers that immediately read
     * [isConfigured] / use any other API see a consistent state. Side effects
     * that don't gate readiness — tearing down a prior [PlayBillingCoordinator]
     * if the SDK is being re-configured — run on [Dispatchers.IO] via [ioScope]
     * so the calling thread (typically Main, at app startup) is not blocked
     * on `BillingClient.endConnection()` or unregistering network callbacks.
     */
    public fun configure(context: Context, config: ZeroSettleConfig) {
        // Re-configure: tear down any prior Play coordinator / poller so they don't leak.
        // Coordinator.shutdown() is idempotent (clears queue, runCatching on the
        // network callback unregister) so launching off-thread is safe even if
        // the new coordinator boots before the old one finishes tearing down.
        playCoordinator?.let { coord -> ioScope.launch { coord.shutdown() } }
        playCoordinator = null
        entitlementPoller?.stop()
        entitlementPoller = null
        ucbConfigRepository = null
        _isUcbEnabled.value = false
        this.appContext = context.applicationContext
        this.config = config
        this.identityStore = IdentityStore(context.applicationContext)
        this.offerDismissalStore = com.zerosettle.sdk.offers.OfferDismissalStore(context.applicationContext)
        this.backend = Backend(
            baseUrl = config.baseUrlOverride ?: DEFAULT_BASE_URL,
            publishableKey = config.publishableKey,
            sdkVersion = sdkVersion,
        )
        this.ucbConfigRepository = com.zerosettle.sdk.billing.UcbConfigRepository(this.backend!!)
        this.entitlementPoller = com.zerosettle.sdk.entitlements.EntitlementPoller(
            backend = this.backend!!,
            userIdProvider = { _currentUserId.value },
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
     * the SDK isn't configured. Called from [configure] and re-called from [logout] +
     * [bootstrap] so a subsequent [identify] / fresh UCB config re-enables Play sync
     * against the fresh scope.
     *
     * **UCB config capture**: The coordinator's `BillingClient` bakes UCB enablement
     * at construction (`enableUserChoiceBilling` is a builder setter); StateFlow
     * reactivity isn't useful here. We read [UcbConfigRepository.config]'s current
     * value snapshot. From [configure] this resolves to [UcbConfig.Disabled] (the
     * repo's default before [bootstrap] runs `refresh()`); [bootstrap] rebuilds the
     * coordinator after `refresh()` so the freshly-fetched config takes effect.
     */
    private fun buildPlayCoordinator(): com.zerosettle.sdk.billing.PlayBillingCoordinator? {
        val cfg = config ?: return null
        val ctx = appContext ?: return null
        val be = backend ?: return null
        if (!cfg.syncPlayPurchases) return null
        val ucbCfg = ucbConfigRepository?.config?.value ?: com.zerosettle.sdk.billing.UcbConfig.Disabled
        return com.zerosettle.sdk.billing.PlayBillingCoordinator(
            context = ctx,
            backend = be,
            scope = scope.scope,
            logger = cfg.logger,
            strictAck = cfg.strictAck,
            userIdProvider = { _currentUserId.value },
            obfuscatedAccountIdProvider = {
                _currentUserId.value?.let { uid -> AppAccountToken.derive(uid, ctx.packageName).toString() }
            },
            customerNameProvider = { customerName },
            customerEmailProvider = { customerEmail },
            // Routes purchase finalize between consume (consumables) and
            // acknowledge (everything else). The lookup key is a Play Console
            // SKU (`Purchase.products[0]`); the forward path launches the flow
            // with `playProductId ?: id`, so the reverse lookup must prefer a
            // `playProductId` match (falling back to `id`). Matching `id` only
            // returned null whenever `playProductId != id` — the normal case —
            // routing consumables to acknowledge and trapping re-purchase in
            // ITEM_ALREADY_OWNED.
            productTypeLookup = { sku ->
                com.zerosettle.sdk.billing.productTypeForPlaySku(_products.value, sku)
            },
            onEntitlementsMayHaveChanged = {
                scope.scope.launch { restoreEntitlements() }
                entitlementPoller?.pollNow()
            },
            onPendingClaim = { pc -> _pendingClaims.value = _pendingClaims.value + pc },
            emitEvent = { e -> _events.tryEmit(e) },
            // Deferred-bridge: resolve any in-flight purchaseViaPlayBilling() caller
            // when the listener-driven sync confirms or fails. `?.complete` /
            // `?.completeExceptionally` are no-ops when the slot is null — that's
            // the redelivery-on-relaunch path (no awaiter armed for this purchase).
            onPurchaseSynced = { txnId -> pendingPlayPurchaseDeferred?.complete(txnId) },
            onPurchaseFailed = { err -> pendingPlayPurchaseDeferred?.completeExceptionally(err) },
            ucbConfig = ucbCfg,
            ucbCheckoutLauncher = com.zerosettle.sdk.billing.StripeCheckoutLauncher(
                context = ctx,
                backend = be,
                publishableKey = cfg.publishableKey,
                isSandbox = cfg.isSandbox,
                merchantDisplayName = DEFAULT_MERCHANT_DISPLAY_NAME,
                logger = cfg.logger,
                // UCB completion bridge — when the StripePaymentSheet activity
                // returns a Completed outcome, resolve any in-flight
                // [purchaseViaPlayBilling] caller with the backend's canonical
                // `transaction_ref` (a `ucb_*` string, matching the standard
                // Play-sync path's wire shape). The webhook subsequently fans
                // the new entitlement out via the next [restoreEntitlements] /
                // poll; we don't gate the awaiter on entitlement
                // materialisation — PaymentSheet success IS the user-visible
                // purchase success.
                onResult = { outcome ->
                    when (outcome) {
                        is com.zerosettle.sdk.billing.UcbPurchaseOutcome.Completed -> {
                            // The canonical `ucb_*` ref resolves on
                            // `GET /v1/iap/transactions/{id}/`. When it's null
                            // (older backend that didn't emit `transaction_ref`)
                            // complete with an empty string — `purchaseViaPlayBilling`
                            // then builds a local CheckoutTransaction rather than
                            // fetching, the same graceful path the normal Play
                            // sync path (RC-F) uses. `externalTransactionId` is
                            // NOT a resolvable transaction id and would also
                            // 404, so it is not used as a fallback.
                            pendingPlayPurchaseDeferred?.complete(outcome.transactionRef ?: "")
                        }
                        is com.zerosettle.sdk.billing.UcbPurchaseOutcome.Canceled -> {
                            pendingPlayPurchaseDeferred?.completeExceptionally(
                                ZeroSettleError.PurchaseCancelled,
                            )
                        }
                        is com.zerosettle.sdk.billing.UcbPurchaseOutcome.Failed -> {
                            pendingPlayPurchaseDeferred?.completeExceptionally(
                                ZeroSettleError.CheckoutFailed(outcome.message),
                            )
                        }
                    }
                },
            ),
        )
    }

    /**
     * Display name surfaced inside Stripe's PaymentSheet header. Hardcoded
     * for now — the per-tenant value will flow in via [UcbConfig] (or a
     * dedicated branding field) once Phase 3's dashboard ships.
     */
    private const val DEFAULT_MERCHANT_DISPLAY_NAME: String = "ZeroSettle"

    // ─── Identity ───────────────────────────────────────────────────────────

    private val _isBootstrapped = MutableStateFlow(false)
    public val isBootstrapped: StateFlow<Boolean> = _isBootstrapped.asStateFlow()

    private val _currentUserId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /**
     * The currently-identified user id, or null if no identity has been set.
     * Updated by `identify(.user)`, `identify(.anonymous)` (synthetic anon-uuid),
     * and `logout()`. Exposed publicly so cross-platform bridges (Flutter plugin)
     * can read the canonical source without reflection or context-aware
     * accessor patterns.
     */
    public val currentUserId: kotlinx.coroutines.flow.StateFlow<String?> = _currentUserId.asStateFlow()
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
                _currentUserId.value = identity.id
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
                _currentUserId.value = uuid
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

        // Refresh the tenant's UCB configuration BEFORE building the
        // PlayBillingCoordinator that will drive purchases. The coordinator's
        // BillingClient bakes UCB enablement at construction (it's a builder
        // setter), so a StateFlow-reactive approach isn't viable — we need
        // the fresh config in hand before we wire the BillingClient.
        //
        // Best-effort: a failed fetch (404 from a tenant that hasn't shipped
        // UCB, or a transient backend hiccup) leaves the cached config at
        // [UcbConfig.Disabled] so the SDK falls back to standard Play
        // Billing. Bootstrap still proceeds.
        ucbConfigRepository?.refresh()?.onFailure {
            config?.logger?.warn(
                "ucb",
                "config fetch failed: ${it.message}; defaulting to disabled",
            )
        }
        _isUcbEnabled.value = ucbConfigRepository?.config?.value?.isEnabled ?: false

        // Rebuild the coordinator so the fresh UCB config takes effect.
        // The one configure() built was constructed against the repo's
        // default `Disabled` value (the fetch hadn't run yet) — it was
        // never `.start()`ed, so a synchronous swap is safe.
        val oldCoordinator = playCoordinator
        playCoordinator = buildPlayCoordinator()
        // The old coordinator was built but never connected — its BillingClient
        // has no active connection to tear down. `shutdown()` is idempotent;
        // run it off-thread so we don't block bootstrap on the no-op
        // `endConnection()` call (which marshals through Play services
        // even with no live connection).
        oldCoordinator?.let { coord -> ioScope.launch { coord.shutdown() } }

        playCoordinator?.start()
        entitlementPoller?.start(scope.scope)
        return Result.success(products)
    }

    /**
     * Update customer metadata for subsequent checkouts / syncs.
     *
     * In-memory state ([customerName] / [customerEmail], read by every
     * downstream consumer — `PlayBillingCoordinator`, the checkout request
     * builder) updates synchronously so the next `purchase()` sees the new
     * values immediately. DataStore persistence is fire-and-forget on
     * [ioScope] so callers that invoke this from the Main thread (typical
     * for UI-driven name/email edits) are not blocked on disk I/O.
     *
     * **Concurrency note:** in-memory state is authoritative for every
     * runtime read. Two rapid `setCustomer(...)` calls launch two ioScope
     * writers; DataStore serializes via its per-store mutex but does not
     * guarantee FIFO acquisition order, so the *persisted* value across
     * process death could reflect an earlier call. This is acceptable — the
     * next live `setCustomer` (or identify) immediately re-overwrites both
     * in-memory + DataStore. Adopters who require deterministic last-write
     * persistence should serialize their own callers.
     */
    public fun setCustomer(name: String? = null, email: String? = null) {
        if (name != null) customerName = name
        if (email != null) customerEmail = email
        identityStore?.let { store ->
            // Snapshot the fields the writer should persist; subsequent
            // setCustomer() calls update in-memory state for the next
            // launch but don't reach into this coroutine.
            val nameToPersist = customerName
            val emailToPersist = customerEmail
            ioScope.launch { store.setCustomer(name = nameToPersist, email = emailToPersist) }
        }
    }

    /**
     * Clear identity, customer metadata, sync queue. Cancels in-flight background work.
     *
     * In-memory state (StateFlows, identity, customer metadata) is cleared
     * synchronously so observers see a logged-out SDK before the method
     * returns. The Play coordinator shutdown and DataStore clear are
     * dispatched to [ioScope]; the calling thread (typically Main, called
     * from a UI handler) is not blocked on `BillingClient.endConnection()`
     * or DataStore disk writes.
     */
    public fun logout() {
        // Snapshot the old coordinator + identity store BEFORE we recreate
        // them. The shutdown / clear runs on ioScope after this method
        // returns, against these snapshotted references.
        val oldCoordinator = playCoordinator
        val oldIdentityStore = identityStore
        entitlementPoller?.stop()
        // Clear the pending Play sync queue SYNCHRONOUSLY (not via ioScope)
        // before we relinquish the coordinator reference. The queue is keyed
        // by user identity; once we set _currentUserId to null and rebuild
        // the coordinator, an in-flight sync from the old user must not be
        // observable. The clear() call is a single DataStore key removal —
        // bounded, and small enough that the calling thread isn't ANR-prone
        // here (the heavy work is `BillingClient.endConnection()` below).
        oldCoordinator?.let { coord -> runBlocking { coord.queue.clear() } }
        playCoordinator = null
        scope.reset()
        _currentUserId.value = null
        customerName = null
        customerEmail = null
        _isBootstrapped.value = false
        _products.value = emptyList()
        _entitlements.value = emptyList()
        _pendingClaims.value = emptyList()
        _pendingActions.value = emptyList()
        // Rebuild the coordinator synchronously so a subsequent identify()
        // re-enables Play sync immediately. The OLD coordinator's shutdown
        // (clearing the queue, unregistering the network callback, ending
        // the Billing connection) is fire-and-forget on ioScope —
        // PlayBillingCoordinator.shutdown is idempotent and the new
        // coordinator boots its own BillingClient session.
        playCoordinator = buildPlayCoordinator()
        ioScope.launch {
            oldCoordinator?.shutdown()
            oldIdentityStore?.clear()
        }
    }

    // ─── Observables ────────────────────────────────────────────────────────

    /**
     * Whether User Choice Billing (UCB) is enabled for this app/market, as
     * determined by the server-side `PlayBillingConfig` fetched at bootstrap.
     *
     * When `true`, host apps should present a **single unified purchase entry
     * point** — Google's choice screen handles routing between web checkout and
     * Play Billing. When `false` (the default before bootstrap completes, and
     * for apps that have not opted into UCB), apps may show separate web and
     * Play purchase paths.
     *
     * Reflects the server's `PlayBillingConfig.is_enabled` field. Starts
     * `false`, updates once after [identify] completes bootstrap, and resets
     * to `false` on [configure] (new tenant/key).
     *
     * Tenant/market-scoped: because it reflects the server `PlayBillingConfig`
     * and not the signed-in user, it is reset on [configure] but intentionally
     * **not** on [logout] — a logout/re-identify cycle does not change UCB
     * enablement.
     */
    private val _isUcbEnabled = MutableStateFlow(false)
    public val isUcbEnabled: StateFlow<Boolean> = _isUcbEnabled.asStateFlow()

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

    internal fun requireUserId(): String = _currentUserId.value ?: throw ZeroSettleError.UserNotIdentified
    internal fun currentUserIdOrNull(): String? = _currentUserId.value
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
     * Fetch the identified user's full transaction history
     * (`GET /v1/iap/transaction-history/`) as a typed list. Mirrors iOS
     * Kit's `fetchTransactionHistory() -> [CheckoutTransaction]`.
     *
     * @return `Result.success(List<CheckoutTransaction>)` on a 2xx + decodable
     *   body; `Result.failure(ZeroSettleError.UserNotIdentified)` when no user
     *   is identified; `Result.failure(ZeroSettleError.BackendError(...))` for
     *   non-2xx responses *or* a 2xx response that fails to decode.
     */
    public suspend fun fetchTransactionHistory(): Result<List<com.zerosettle.sdk.models.CheckoutTransaction>> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        val be = backend ?: return Result.failure(ZeroSettleError.NotConfigured)
        return be.fetchTransactionHistory(uid)
    }

    // ─── Web checkout (Stripe) ─────────────────────────────────────────────

    /**
     * Tracks an in-flight web checkout. The Deferred resolves with the
     * transactionId when [completeWebCheckout] is called, or completes
     * exceptionally with a [ZeroSettleError] when the callback signals
     * cancel / failure. Lives on the SDK singleton so it survives Activity
     * recreations within a single SDK session (Custom Tab → deep-link
     * return → caller observes via the same suspending [purchase] call).
     * One in-flight checkout at a time — concurrent [purchase] calls fail
     * fast with [ZeroSettleError.CheckoutInFlight].
     */
    @Volatile private var pendingCheckoutDeferred: CompletableDeferred<String>? = null

    /**
     * Tracks an in-flight Play Billing purchase. Resolves with the backend's
     * `transactionId` when [com.zerosettle.sdk.billing.PurchaseSyncProcessor]
     * confirms the purchase, or completes exceptionally with a [ZeroSettleError]
     * for the failure branches (sync 5xx, ownership conflict, not_owned, pending).
     *
     * Lives on the SDK singleton (same lifecycle as [pendingCheckoutDeferred]) so
     * the listener-driven Play flow — which runs on the coordinator's
     * scope — can bridge back to the suspending [purchaseViaPlayBilling] caller
     * even across Activity recreations within a single SDK session.
     *
     * One in-flight purchase at a time — concurrent calls fail fast with
     * [ZeroSettleError.CheckoutInFlight].
     *
     * Redelivery semantics: when Play redelivers an unacknowledged purchase from
     * a prior session, this slot is `null` (the caller is gone). Resolving via
     * `?.complete(...)` makes that path a no-op; the existing sync + events
     * stream behaviour is unchanged.
     */
    @Volatile private var pendingPlayPurchaseDeferred: CompletableDeferred<String>? = null

    /**
     * High-level, awaitable web checkout. Drives the full flow:
     *
     *  1. Creates a Stripe Checkout session for [productId].
     *  2. Launches it in a Chrome Custom Tab (or external browser when the
     *     backend asks for [com.zerosettle.sdk.checkout.CheckoutPresentation.BROWSER]).
     *  3. Suspends until the host app feeds the `zerosettle://checkout/return…`
     *     deep link back via [completeWebCheckout].
     *  4. Refetches the hydrated transaction record from the backend.
     *
     * Mirrors iOS `ZeroSettle.shared.purchase(_:)`. The intermediate URL is an
     * implementation detail — callers who want custom WebView presentation
     * should use [OfferManager.checkoutUrl] instead (the explicit escape hatch).
     *
     * Concurrent calls fail with [ZeroSettleError.CheckoutInFlight] — the SDK
     * serializes one checkout at a time.
     *
     * If the awaiting coroutine is cancelled before the deep-link return
     * (e.g., the host scope is torn down), the [CancellationException]
     * propagates per Kotlin coroutine conventions; the in-flight bridge slot
     * is cleared so a subsequent [purchase] call isn't blocked. The SDK's
     * deep-link side effects (entitlement refresh, event emission) still fire
     * normally when the callback eventually arrives.
     *
     * @param presentation Optional per-call presentation override. When non-null,
     *   beats whatever the backend response specifies. Mirrors iOS Kit's
     *   `presentation: CheckoutType?` parameter on `ZeroSettle.shared.purchase`.
     *   Use [com.zerosettle.sdk.checkout.CheckoutPresentation.INLINE] /
     *   [com.zerosettle.sdk.checkout.CheckoutPresentation.SHEET] to route
     *   through the in-app [com.zerosettle.sdk.checkout.ZeroSettleWebViewActivity]
     *   regardless of server configuration. Use
     *   [com.zerosettle.sdk.checkout.CheckoutPresentation.BROWSER] for external
     *   browser. Pass `null` to defer to the server's response field (today
     *   that defaults to [com.zerosettle.sdk.checkout.CheckoutPresentation.CUSTOM_TAB]
     *   since backend doesn't emit a presentation hint on regular
     *   `create_checkout_session` responses yet).
     *
     * When the resolved presentation routes to a Chrome Custom Tab and
     * [activity] is a [androidx.lifecycle.LifecycleOwner], the SDK installs a
     * one-shot lifecycle watcher that auto-cancels the in-flight checkout if
     * the host Activity resumes without a deep-link arriving (Chrome Custom
     * Tabs don't notify the host on user dismissal). Hosts whose Activity is
     * not a `LifecycleOwner` can call [releasePendingCheckout] manually from
     * `onResume()`. The in-app WebView and external browser paths handle
     * their own cancellation and don't need either mechanism.
     */
    public suspend fun purchase(
        activity: android.app.Activity,
        productId: String,
        presentation: com.zerosettle.sdk.checkout.CheckoutPresentation? = null,
    ): Result<com.zerosettle.sdk.models.CheckoutTransaction> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        val be = backend ?: return Result.failure(ZeroSettleError.NotConfigured)

        // Arm the bridge BEFORE any suspension so two parallel callers can't both
        // pass the concurrent-call guard and clobber each other's deferred. The
        // assignment + null-check are both on the JVM main reference, and pairs
        // of Activity-launched coroutines on different dispatchers can interleave
        // around any `withContext(IO)` inside createWebCheckout otherwise.
        val deferred: CompletableDeferred<String> = synchronized(this) {
            if (pendingCheckoutDeferred != null) return Result.failure(ZeroSettleError.CheckoutInFlight)
            CompletableDeferred<String>().also { pendingCheckoutDeferred = it }
        }

        val createResult = be.createWebCheckout(
            userId = uid, productId = productId, playPurchaseToken = null,
            customerName = customerName, customerEmail = customerEmail,
        )
        val resp = createResult.getOrElse {
            // Network/decoding error before we ever launched the tab — release
            // the slot so the next purchase() call isn't blocked. _pendingCheckout
            // was never set true on this path so it doesn't need resetting.
            pendingCheckoutDeferred = null
            return Result.failure(it)
        }

        // Everything past this point — Custom Tab launch, await, fetchTransaction —
        // runs inside a single try/finally that clears BOTH the bridge slot AND
        // the public _pendingCheckout flag on every exit path. The launch calls
        // can throw (ActivityNotFoundException, SecurityException, etc.); without
        // the finally, the slot would leak and subsequent purchase() calls would
        // be permanently locked out with CheckoutInFlight. The flag reset also
        // covers cancellation and ZeroSettleError-from-await paths so public
        // StateFlow consumers don't see a stale `true` after a cancelled checkout.
        _pendingCheckout.value = true
        try {
            // Per-call override beats server-driven setting. iOS pattern:
            // `effectiveType = presentation ?? checkoutType`. We have no global
            // `checkoutType` field on Android yet (remoteConfig surface is absent
            // — see the parity audit), so the fallback is server response →
            // CUSTOM_TAB.
            val effectivePresentation = presentation ?: resp.checkoutPresentation
            when (effectivePresentation) {
                com.zerosettle.sdk.checkout.CheckoutPresentation.BROWSER ->
                    com.zerosettle.sdk.checkout.WebCheckoutFlow.launchExternalBrowser(activity, resp.checkoutUrl)
                com.zerosettle.sdk.checkout.CheckoutPresentation.INLINE,
                com.zerosettle.sdk.checkout.CheckoutPresentation.SHEET ->
                    com.zerosettle.sdk.checkout.WebCheckoutFlow.launchWebView(activity, resp.checkoutUrl)
                // CUSTOM_TAB or null → safe default
                else -> {
                    com.zerosettle.sdk.checkout.WebCheckoutFlow.launchCustomTab(activity, resp.checkoutUrl)
                    // Chrome Custom Tabs don't notify the host Activity on user
                    // dismissal. Install a one-shot Lifecycle watcher (when the
                    // Activity is a LifecycleOwner — modern ComponentActivity /
                    // AppCompatActivity / FlutterActivity all are) that auto-
                    // cancels the in-flight checkout if the host resumes after
                    // a pause without a deep-link arriving. Adopters with
                    // non-LifecycleOwner activities must call
                    // releasePendingCheckout() manually from onResume().
                    val lifecycleOwner = activity as? androidx.lifecycle.LifecycleOwner
                    if (lifecycleOwner != null) {
                        installCustomTabDismissalWatcher(lifecycleOwner, deferred)
                    }
                }
            }

            val transactionId: String = try {
                deferred.await()
            } catch (e: ZeroSettleError) {
                // The deferred was completed exceptionally by completeWebCheckout
                // (cancel / failed-status). Surface as Result.failure rather than
                // propagating — that's the contract for "normal" purchase errors.
                return Result.failure(e)
            }
            // CancellationException intentionally propagates per Kotlin coroutine
            // convention — the `finally` block below still runs and resets state.

            return be.fetchTransaction(transactionId)
        } finally {
            pendingCheckoutDeferred = null
            _pendingCheckout.value = false
        }
    }

    /**
     * Feed the `zerosettle://checkout/return…` deep link back into the SDK. The host
     * app calls this from the Activity that received the redirect intent. Clears
     * [pendingCheckout]; on success, refreshes entitlements, emits a
     * [ZeroSettleEvent.PurchaseSucceeded], and resolves any in-flight [purchase]
     * call with the transactionId so it can refetch the hydrated record.
     *
     * Side effects (entitlement refresh, event emission, pendingCheckout reset)
     * fire whether or not a [purchase] call is currently awaiting — the deep link
     * is the source of truth for what just happened in the browser.
     */
    public suspend fun completeWebCheckout(callbackUrl: String): Result<Unit> {
        val parsed = com.zerosettle.sdk.checkout.WebCheckoutFlow.parseCallback(callbackUrl)
            ?: return Result.failure(ZeroSettleError.CheckoutFailed("not_a_callback_url"))
        _pendingCheckout.value = false
        return when (parsed) {
            is com.zerosettle.sdk.checkout.WebCheckoutFlow.CallbackResult.Succeeded -> {
                val txnId = parsed.transactionId ?: ""
                // Resolve the in-flight purchase() awaiter FIRST and unconditionally —
                // the checkout page already redirected with `status=success`, so the
                // user-visible purchase succeeded even if a subsequent entitlement
                // refresh fails (network blip). Gating the resolve on restoreEntitlements
                // would leave the awaiter hanging on transient failures.
                if (txnId.isNotEmpty()) {
                    pendingCheckoutDeferred?.complete(txnId)
                } else {
                    pendingCheckoutDeferred?.completeExceptionally(
                        ZeroSettleError.CheckoutFailed("missing_transaction_id"),
                    )
                }
                restoreEntitlements().map {
                    _events.tryEmit(ZeroSettleEvent.PurchaseSucceeded(productId = "", transactionId = txnId))
                }
            }
            com.zerosettle.sdk.checkout.WebCheckoutFlow.CallbackResult.Cancelled -> {
                pendingCheckoutDeferred?.completeExceptionally(ZeroSettleError.PurchaseCancelled)
                Result.failure(ZeroSettleError.PurchaseCancelled)
            }
            is com.zerosettle.sdk.checkout.WebCheckoutFlow.CallbackResult.Failed -> {
                _events.tryEmit(ZeroSettleEvent.PurchaseFailed(productId = "", reason = parsed.reason))
                pendingCheckoutDeferred?.completeExceptionally(ZeroSettleError.CheckoutFailed(parsed.reason))
                Result.failure(ZeroSettleError.CheckoutFailed(parsed.reason))
            }
        }
    }

    /**
     * Cancel any in-flight web checkout that hasn't received a deep-link
     * return. Resolves [pendingCheckout] back to `false` and completes the
     * awaiting [purchase] coroutine with `Result.failure(PurchaseCancelled)`.
     *
     * Hosts that launch a [com.zerosettle.sdk.checkout.CheckoutPresentation.CUSTOM_TAB]
     * flow should call this from their Activity's `onResume()` when control
     * returns without a deep-link intent — Chrome Custom Tabs don't notify the
     * host on user dismissal, so a stale slot would prevent any subsequent
     * purchase. The in-app WebView and external browser paths handle their own
     * cancellation and do NOT need this call.
     *
     * Note: hosts whose launch Activity is an
     * [androidx.lifecycle.LifecycleOwner] (every modern `ComponentActivity` /
     * `AppCompatActivity` / `FlutterActivity` is) get auto-cancel via a
     * Lifecycle watcher installed by [purchase] on the Custom Tab path; manual
     * calls to this method are only required for non-LifecycleOwner hosts.
     *
     * Adopters can also call this defensively from `onStop` / app-background
     * transitions if their UX requires it. Idempotent: no-op when no checkout
     * is in flight.
     *
     * **Concurrency note.** The `synchronized(this)` snapshot below pairs with
     * the same lock taken in [purchase] when arming the slot. The slot
     * clear in [purchase]'s `finally` block (line ~552) is a bare null-write
     * NOT under the lock — that's intentional and harmless: both writes go to
     * null (idempotent), and `completeExceptionally` on an already-completed
     * deferred is a no-op. The lock here matters for the
     * [installCustomTabDismissalWatcher] coroutine vs. a concurrent fresh
     * [purchase] call — without it the watcher could race against a freshly-
     * armed slot belonging to a different in-flight purchase.
     */
    public fun releasePendingCheckout() {
        val deferred = synchronized(this) {
            pendingCheckoutDeferred.also { pendingCheckoutDeferred = null }
        } ?: return
        deferred.completeExceptionally(ZeroSettleError.PurchaseCancelled)
        _pendingCheckout.value = false
    }

    /**
     * Install a one-shot Lifecycle observer on [lifecycleOwner] that calls
     * [releasePendingCheckout] when:
     *
     *  - the host Activity has gone through `ON_PAUSE` at least once (the
     *    Custom Tab launch pauses the host), AND
     *  - subsequently fires `ON_RESUME` (control returned), AND
     *  - after a 500ms grace window the in-flight slot ([targetDeferred]) is
     *    still the one this watcher was installed for AND hasn't been resolved
     *    (no deep-link arrived).
     *
     * The grace delay gives the deep-link intent a chance to land — when a
     * checkout completes successfully, the host resumes via the deep-link
     * Activity, [completeWebCheckout] resolves the deferred, and this
     * watcher's poll becomes a no-op.
     *
     * The observer self-unregisters on `ON_DESTROY` so the watcher doesn't
     * outlive the host Activity (process-death / config-kill leak guard).
     *
     * **`addObserver` replays state.** When attached to a currently-RESUMED
     * lifecycle, AndroidX synthesizes `ON_CREATE` / `ON_START` / `ON_RESUME`
     * events to bring the observer up to date. The [wasPaused] flag prevents
     * a spurious fire on install — only the RESUME *following* a PAUSE counts
     * as a Custom Tab return.
     *
     * **Main-thread requirement.** Lifecycle add/remove must run on the main
     * thread. [purchase] is `suspend` and may be called from any dispatcher,
     * so the install dispatches to [kotlinx.coroutines.Dispatchers.Main] via
     * [scope].
     *
     * @param targetDeferred the deferred this watcher is bound to. When the
     *   timer fires we only release the slot if the current
     *   [pendingCheckoutDeferred] is the same instance — concurrent purchase
     *   serialization (CheckoutInFlight) means this should always hold, but
     *   the identity check defends against bizarre reentrancy bugs.
     */
    @androidx.annotation.VisibleForTesting
    internal fun installCustomTabDismissalWatcher(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        targetDeferred: CompletableDeferred<String>,
    ) {
        scope.scope.launch(Dispatchers.Main.immediate) {
            var wasPaused = false
            val observer = object : androidx.lifecycle.LifecycleEventObserver {
                override fun onStateChanged(
                    source: androidx.lifecycle.LifecycleOwner,
                    event: androidx.lifecycle.Lifecycle.Event,
                ) {
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                            wasPaused = true
                        }
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                            if (!wasPaused) return
                            // One-shot: detach BEFORE scheduling the grace
                            // window so a subsequent ON_PAUSE/ON_RESUME pair
                            // doesn't queue a second poll.
                            source.lifecycle.removeObserver(this)
                            scope.scope.launch {
                                kotlinx.coroutines.delay(CUSTOM_TAB_GRACE_MS)
                                val stillArmed = synchronized(this@ZeroSettle) {
                                    pendingCheckoutDeferred === targetDeferred &&
                                        !targetDeferred.isCompleted
                                }
                                if (stillArmed) releasePendingCheckout()
                            }
                        }
                        androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> {
                            // Drop our strong-reference closure capture if the
                            // host Activity dies before we fire (config-change
                            // kill, process death). Without this the observer
                            // is a memory leak.
                            source.lifecycle.removeObserver(this)
                        }
                        else -> Unit
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
        }
    }

    /**
     * Grace window between an `ON_RESUME` after a Custom Tab dismiss and the
     * auto-cancel firing. Gives a deep-link intent a chance to land first
     * (when checkout completed successfully). 500ms is empirically generous —
     * the deep-link intent dispatch is fast.
     */
    private const val CUSTOM_TAB_GRACE_MS: Long = 500L

    // ─── Native Play Billing ───────────────────────────────────────────────

    /**
     * High-level, awaitable Play Billing purchase. Drives the full flow:
     *
     *  1. Launches the Play Billing dialog for [productId] via `BillingClient`.
     *  2. Suspends until the listener-driven sync path
     *     ([com.zerosettle.sdk.billing.PurchaseSyncProcessor]) resolves the
     *     in-flight bridge — backend confirms `owned=true` and the SDK acks the
     *     purchase (3-day-window rule — see processor docs).
     *  3. Refetches the hydrated transaction record from the backend.
     *
     * Mirrors iOS `purchaseViaStoreKit()`. Like [purchase], the intermediate
     * `transactionId` is an implementation detail — callers receive a fully
     * hydrated [CheckoutTransaction][com.zerosettle.sdk.models.CheckoutTransaction].
     *
     * Concurrent calls fail with [ZeroSettleError.CheckoutInFlight] — the SDK
     * serializes one Play purchase at a time (same variant as web checkout).
     *
     * Failure modes:
     *  - Dialog-launch failure (product not found, billing client not connected,
     *    user cancelled) returns immediately without suspending — no listener
     *    fire is coming so awaiting would hang.
     *  - Sync 5xx / ownership conflict / `not_owned` / `PENDING` purchase state
     *    resolve the bridge exceptionally; the caller observes a [Result.failure].
     *
     * Redelivery: when Play redelivers an unacknowledged purchase on next app
     * launch, no caller is awaiting; the bridge no-ops gracefully and the
     * normal sync + events-stream behaviour proceeds unchanged.
     *
     * If the awaiting coroutine is cancelled before the sync resolves
     * (e.g., host scope torn down), the [kotlinx.coroutines.CancellationException]
     * propagates per Kotlin convention; the in-flight slot is cleared so a
     * subsequent call isn't blocked.
     *
     * Requires `ZeroSettleConfig.syncPlayPurchases = true`.
     */
    public suspend fun purchaseViaPlayBilling(
        activity: android.app.Activity,
        productId: String,
    ): Result<com.zerosettle.sdk.models.CheckoutTransaction> {
        if (currentUserIdOrNull() == null) return Result.failure(ZeroSettleError.UserNotIdentified)
        val coord = playCoordinator ?: return Result.failure(ZeroSettleError.NotConfigured)
        val be = backend ?: return Result.failure(ZeroSettleError.NotConfigured)
        val product = product(productId) ?: return Result.failure(ZeroSettleError.ProductNotFound(productId))

        // Arm the bridge BEFORE launching the dialog so the listener fire (which
        // may arrive very quickly on a fast device) finds a deferred to resolve.
        // synchronized() ensures two parallel callers can't both pass the
        // concurrent-call guard and clobber each other's deferred — same pattern
        // as A2's purchase() web-checkout flow.
        val deferred: CompletableDeferred<String> = synchronized(this) {
            if (pendingPlayPurchaseDeferred != null) return Result.failure(ZeroSettleError.CheckoutInFlight)
            CompletableDeferred<String>().also { pendingPlayPurchaseDeferred = it }
        }

        // Launch the Play dialog. If THIS fails (product details query failed,
        // billing client refused to launch, user immediately cancelled), no
        // listener fire is coming — clear the slot and return BEFORE the
        // suspend point so the caller isn't left hanging on a deferred that
        // will never resolve.
        val launchResult = coord.purchaseViaPlayBilling(activity, product)
        if (launchResult.isFailure) {
            pendingPlayPurchaseDeferred = null
            return Result.failure(launchResult.exceptionOrNull() ?: ZeroSettleError.CheckoutFailed("launch_failed"))
        }

        // try/finally clears the slot on every exit path (success, throw, cancel).
        // Matches A2's discipline — without it, an unexpected exception inside
        // fetchTransaction would leak the slot and lock out future calls.
        try {
            val transactionId: String = try {
                deferred.await()
            } catch (e: ZeroSettleError) {
                // Bridge was completed exceptionally from the sync processor —
                // surface as Result.failure rather than propagating.
                return Result.failure(e)
            }
            // CancellationException intentionally propagates per Kotlin coroutine
            // convention — the `finally` below still runs and resets state.

            // `transactionId` is the canonical `txn_*` ref from the sync
            // response — `fetchTransaction` resolves it correctly. When it's
            // empty the backend is older and did not emit `transaction_ref`;
            // fetching `""` would 404, so build a local CheckoutTransaction
            // from what the SDK already has. A completed Play purchase must
            // always surface as Result.success.
            if (transactionId.isEmpty()) {
                return Result.success(buildLocalPlayTransaction(productId, product))
            }
            return be.fetchTransaction(transactionId)
        } finally {
            pendingPlayPurchaseDeferred = null
        }
    }

    /**
     * Build a [CheckoutTransaction][com.zerosettle.sdk.models.CheckoutTransaction]
     * locally for a confirmed Play purchase when the backend did not return a
     * canonical `transaction_ref` (older backend). The purchase IS recorded
     * server-side (`owned=true`); this is purely the SDK reconstructing the
     * record it would otherwise have fetched, so the caller still receives a
     * `Result.success`. Price/name come from the catalog [product]'s Play
     * price.
     */
    private fun buildLocalPlayTransaction(
        productId: String,
        product: Product,
    ): com.zerosettle.sdk.models.CheckoutTransaction {
        val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        val playPrice = product.playStorePrice
        return com.zerosettle.sdk.models.CheckoutTransaction(
            id = "",
            productId = productId,
            status = com.zerosettle.sdk.models.CheckoutTransaction.Status.COMPLETED,
            source = com.zerosettle.sdk.models.EntitlementSource.PLAY_STORE,
            purchasedAt = nowIso,
            productName = product.displayName,
            amountCents = playPrice?.amountCents,
            currency = playPrice?.currencyCode,
        )
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
        val ctx = appContext ?: throw ZeroSettleError.NotConfigured
        val logger = config?.logger
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
                    is com.zerosettle.sdk.offers.OfferManager.OfferEvent.EvaluationFailed ->
                        _events.tryEmit(ZeroSettleEvent.OfferEvaluationFailed(e.error.message ?: "offer evaluation failed"))
                }
            },
            executeUpgradeOffer = { from, to -> be.executeUpgradeOffer(uid, from, to).map { } },
            isEclAvailable = {
                val forced = eclAvailabilityOverride
                if (forced != null) {
                    logger?.warn(
                        "OfferManager",
                        "ECL availability OVERRIDDEN to $forced via ZeroSettle.eclAvailabilityOverride " +
                            "(testing only — must be null in production builds)",
                    )
                    forced
                } else {
                    val eclClient = ExternalContentLinkClient(ctx)
                    val available = try {
                        eclClient.isAvailable()
                    } finally {
                        eclClient.endConnection()
                    }
                    logger?.info("OfferManager", "ECL availability (Play Billing query): available=$available")
                    available
                }
            },
            launchSwitchAndSave = { activity -> launchSwitchAndSave(activity) },
            logger = logger,
        )
    }

    // ─── Static dismissal helpers ──────────────────────────────────────────
    //
    // Used by the Flutter plugin's `zerosettle/offer_manager_static` channel;
    // mirror iOS `ZSOfferManager`'s class-level dismissal API. Userid-keyed so
    // a single device serving multiple users keeps each user's dismissal
    // independent (matching iOS).

    /**
     * Whether [userId] has previously dismissed an offer permanently. Resets
     * via [resetOfferDismissedState] (clears all users) or
     * [setOfferDismissed]`(userId, false)` (per-user).
     *
     * Throws [ZeroSettleError.NotConfigured] if [configure] hasn't run.
     */
    public suspend fun isOfferPermanentlyDismissed(userId: String): Boolean {
        val store = offerDismissalStore ?: throw ZeroSettleError.NotConfigured
        return store.isDismissed(userId)
    }

    /**
     * Set the dismissal preference for [userId]. `dismissed=true` marks the
     * offer permanently dismissed for that user; `dismissed=false` clears it.
     * Mirrors the Dart-side `setDismissed` contract at
     * `lib/managers/offer_manager.dart:183` which sends both args.
     *
     * Throws [ZeroSettleError.NotConfigured] if [configure] hasn't run.
     */
    public suspend fun setOfferDismissed(userId: String, dismissed: Boolean) {
        val store = offerDismissalStore ?: throw ZeroSettleError.NotConfigured
        if (dismissed) store.dismiss(userId) else store.undismiss(userId)
    }

    /**
     * Reset all per-user dismissal state. No-arg (matches iOS handler at
     * `ZeroSettlePlugin.swift:251-253` and Dart's
     * `ZSOfferManagerStatics.resetDismissedState()` at
     * `lib/managers/offer_manager.dart:191`). Typically debug-only.
     *
     * Throws [ZeroSettleError.NotConfigured] if [configure] hasn't run.
     */
    public suspend fun resetOfferDismissedState() {
        val store = offerDismissalStore ?: throw ZeroSettleError.NotConfigured
        store.resetAll()
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
            .mapCatching { it.newProductId ?: throw ZeroSettleError.BackendError(statusCode = 200, body = "upgrade response missing product id") }
            .onSuccess { scope.scope.launch { restoreEntitlements() }; entitlementPoller?.pollNow() }
    }

    /**
     * Record that a migration offer converted (`POST /v1/iap/migration-converted/`).
     * [source] is `[UserOffer.SourceStorefront.PLAY_STORE]` for a Play→web migration,
     * `[UserOffer.SourceStorefront.STORE_KIT]` for a StoreKit→web one. [OfferManager]
     * calls this automatically; exposed here for headless / custom-WebView callers.
     */
    public suspend fun trackMigrationConversion(source: com.zerosettle.sdk.models.UserOffer.SourceStorefront): Result<Unit> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        val wire = when (source) {
            com.zerosettle.sdk.models.UserOffer.SourceStorefront.PLAY_STORE -> "play_store"
            com.zerosettle.sdk.models.UserOffer.SourceStorefront.STORE_KIT -> "store_kit"
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
     * `POST /v1/iap/claim-play-entitlement/`. Never auto-invoked; the host app calls
     * this explicitly in response to a `pendingClaims` entry (iOS parity: manual
     * claim only).
     *
     * The `purchaseToken` and `packageName` needed for the Play claim are sourced
     * internally — the token from the matching [PendingClaim] (populated by the
     * conflict sync), the package name from the SDK's own application context.
     * The caller supplies only [productId], matching iOS's
     * `transferStoreKitOwnershipToCurrentUser(productId:)` shape.
     *
     * Failure modes:
     *  - no identified user → [ZeroSettleError.UserNotIdentified]
     *  - SDK not configured → [ZeroSettleError.NotConfigured]
     *  - no matching [PendingClaim] for [productId], or the matching claim has no
     *    `purchaseToken` (older backend that predates the field) →
     *    [ZeroSettleError.NotFound]
     *  - backend rejection (invalid/unknown token, consumable) →
     *    [ZeroSettleError.BackendError]
     *
     * On success the resolved [PendingClaim] is cleared and entitlements are
     * refreshed (`restoreEntitlements()` + a poll) so the entitlement lands.
     *
     * If multiple pending claims share [productId], the first match is used —
     * a known Phase-2 limitation; the realistic case is a single claim per product.
     */
    public suspend fun transferPlayOwnershipToCurrentUser(productId: String): Result<Unit> {
        val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
        val be = backend ?: return Result.failure(ZeroSettleError.NotConfigured)
        val claim = _pendingClaims.value.firstOrNull { it.productId == productId }
            ?: return Result.failure(
                ZeroSettleError.NotFound("No pending claim for product $productId"),
            )
        val purchaseToken = claim.purchaseToken
            ?: return Result.failure(
                ZeroSettleError.NotFound("Pending claim for product $productId has no Play purchase token"),
            )
        val packageName = appContext?.packageName
            ?: return Result.failure(ZeroSettleError.NotConfigured)
        return be.claimPlayEntitlement(uid, productId, purchaseToken, packageName)
            .map { }
            .onSuccess {
                _pendingClaims.value = _pendingClaims.value.filterNot {
                    it.productId == productId && it.purchaseToken == purchaseToken
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

    /**
     * Overload for cross-platform callers (Flutter plugin) that have the
     * transactionId but not the full [PendingAction] sealed-class variant.
     * Resolves the variant from the active [pendingActions] list and forwards
     * to the typed overload.
     *
     * Returns `Result.failure(ZeroSettleError.NotFound)` if the transactionId
     * isn't currently in [_pendingActions]. The Flutter plugin maps this to a
     * `not_found` error code adopters can handle.
     */
    public suspend fun dismissPendingAction(transactionId: String): Result<Unit> {
        val action = _pendingActions.value.firstOrNull { it.transactionId == transactionId }
            ?: return Result.failure(
                ZeroSettleError.NotFound("No pending action for transaction $transactionId")
            )
        return dismissPendingAction(action)
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
        _isUcbEnabled.value = false
        pendingCheckoutDeferred?.cancel()
        pendingCheckoutDeferred = null
        pendingPlayPurchaseDeferred?.cancel()
        pendingPlayPurchaseDeferred = null
        entitlementPoller?.stop()
        entitlementPoller = null
        playCoordinator?.let { coord -> runBlocking { coord.shutdown() } }
        playCoordinator = null
        ucbConfigRepository = null
        offerDismissalStore = null
        appContext = null
        config = null
        backend = null
        identityStore = null
        _currentUserId.value = null
        customerName = null
        customerEmail = null
    }

    internal fun activeUserIdForTesting(): String? = _currentUserId.value
    internal fun customerForTesting(): Pair<String?, String?> = customerName to customerEmail

    /**
     * Test-only: arm [pendingPlayPurchaseDeferred] with a fresh deferred and
     * return it so the caller can `.await()` on it. Mirrors what
     * [purchaseViaPlayBilling] does in production — but skips the
     * `coord.purchaseViaPlayBilling()` dialog launch, which can't run under
     * Robolectric (real `BillingClient` connection required).
     *
     * Throws if a slot is already armed — the helper isn't designed for
     * concurrent test usage.
     */
    internal fun armPendingPlayPurchaseForTesting(): CompletableDeferred<String> = synchronized(this) {
        check(pendingPlayPurchaseDeferred == null) { "pendingPlayPurchaseDeferred already armed" }
        CompletableDeferred<String>().also { pendingPlayPurchaseDeferred = it }
    }

    /**
     * Test-only: arm [pendingCheckoutDeferred] with a fresh deferred and flip
     * `_pendingCheckout` to `true`. Same shape as
     * [armPendingPlayPurchaseForTesting] but for the web slot — lets tests
     * drive [installCustomTabDismissalWatcher] and [releasePendingCheckout]
     * without going through the full [purchase] flow (which requires a
     * configured backend + working Custom Tab launch).
     *
     * Throws if a slot is already armed.
     */
    internal fun armPendingCheckoutForTesting(): CompletableDeferred<String> = synchronized(this) {
        check(pendingCheckoutDeferred == null) { "pendingCheckoutDeferred already armed" }
        CompletableDeferred<String>().also {
            pendingCheckoutDeferred = it
            _pendingCheckout.value = true
        }
    }

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
