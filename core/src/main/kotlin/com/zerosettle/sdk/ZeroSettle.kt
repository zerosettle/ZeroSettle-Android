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

    internal val scope: ZeroSettleScope = ZeroSettleScope()
    private const val SDK_VERSION = "1.0.0"
    private const val DEFAULT_BASE_URL = "https://api.zerosettle.com"

    /** Call once at `Application.onCreate()`. Safe to call again to swap config. */
    public fun configure(context: Context, config: ZeroSettleConfig) {
        this.appContext = context.applicationContext
        this.config = config
        this.identityStore = IdentityStore(context.applicationContext)
        this.backend = Backend(
            baseUrl = config.baseUrlOverride ?: DEFAULT_BASE_URL,
            publishableKey = config.publishableKey,
            sdkVersion = SDK_VERSION,
        )
        _isConfigured.value = true
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
        _isBootstrapped.value = true
        _events.tryEmit(ZeroSettleEvent.EntitlementsRefreshed(count = entResp.entitlements.size))
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
}
