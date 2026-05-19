package com.zerosettle.sdk.billing

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.zerosettle.sdk.core.Backend
import com.zerosettle.sdk.core.ZeroSettleEvent
import com.zerosettle.sdk.core.ZeroSettleLogger
import com.zerosettle.sdk.models.PendingClaim
import com.zerosettle.sdk.models.Product
import com.zerosettle.sdk.models.ProductType
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Map a canonical [Product] to the [BillingClient.ProductType] string Play
 * expects when querying product details / launching the billing flow.
 *
 * Only `AUTO_RENEWABLE_SUBSCRIPTION` maps to `SUBS`; everything else
 * (consumables, non-consumables, non-renewing subscriptions) is `INAPP` on
 * Play. Centralising the mapping prevents the recurrence of the SUBS-default
 * bug where one-time products were silently queried as subscriptions and
 * returned an empty result (surfaced to the caller as `ProductNotFound`).
 */
internal fun playBillingProductType(product: Product): String = when (product.type) {
    ProductType.AUTO_RENEWABLE_SUBSCRIPTION -> BillingClient.ProductType.SUBS
    ProductType.NON_RENEWING_SUBSCRIPTION,
    ProductType.CONSUMABLE,
    ProductType.NON_CONSUMABLE,
    -> BillingClient.ProductType.INAPP
}

/**
 * Decide whether a finalized purchase should go through Play's
 * `consumeAsync` (consumable — releases the SKU so the user can buy it again)
 * or `acknowledgePurchase` (non-consumables and subscriptions — 3-day-window
 * mark-as-processed).
 *
 * Returns `true` ONLY for [ProductType.CONSUMABLE]. Unknown product IDs
 * (catalog miss) fall through to acknowledge — the safe default, since
 * acknowledge is a no-op on an already-acknowledged purchase, while
 * consuming a non-consumable would be destructive.
 *
 * Top-level by design so it's unit-testable without Android context. See
 * [com.zerosettle.sdk.billing.PurchaseFinalizeRoutingTest].
 */
internal fun isConsumable(
    productId: String,
    productTypeLookup: (productId: String) -> ProductType?,
): Boolean = productTypeLookup(productId) == ProductType.CONSUMABLE

/**
 * Resolve a [Product]'s [ProductType] from a finalize-time lookup key.
 *
 * The key is a **Play Console SKU** — `Purchase.products[0]`, the id Play
 * reports on a finalized purchase. The forward purchase path launches the
 * billing flow with `product.playProductId ?: product.id`
 * ([PlayBillingCoordinator.purchaseViaPlayBilling]), so the reverse lookup MUST
 * prefer a [Product.playProductId] match. It falls back to [Product.id] for
 * products whose SKU == id (no separate `playProductId` configured).
 *
 * Matching only [Product.id] (the prior behavior) returns `null` whenever
 * `playProductId != id` — the normal case — which routed consumables to
 * `acknowledge` instead of `consume` and trapped re-purchase in
 * `ITEM_ALREADY_OWNED`.
 *
 * Top-level by design so it's unit-testable without Android context. See
 * [com.zerosettle.sdk.billing.ProductTypeForPlaySkuTest].
 */
internal fun productTypeForPlaySku(products: List<Product>, playSku: String): ProductType? {
    val byPlay = products.firstOrNull { it.playProductId == playSku }
    return (byPlay ?: products.firstOrNull { it.id == playSku })?.type
}

/**
 * Glue between [ZeroSettle][com.zerosettle.sdk.ZeroSettle] and the Play Billing layer.
 * Created at `configure()` when `syncPlayPurchases` is true; the queue is cleared and
 * the connection ended on `logout()` ([shutdown]).
 *
 * Responsibilities: install the [PlayBillingManager] purchase listener → route each
 * observed purchase through [PurchaseSyncProcessor]; run [SubscriptionReconciler] on
 * [start] and after each purchase event; drain the [PlaySyncQueue] on [start], on
 * network regain (via [ConnectivityManager.NetworkCallback]), and after each purchase
 * event.
 */
internal class PlayBillingCoordinator(
    private val context: Context,
    private val backend: Backend,
    private val scope: CoroutineScope,
    private val logger: ZeroSettleLogger,
    private val strictAck: Boolean,
    private val userIdProvider: () -> String?,
    private val obfuscatedAccountIdProvider: () -> String?,
    private val customerNameProvider: () -> String?,
    private val customerEmailProvider: () -> String?,
    // Resolves a Play product id to its canonical [ProductType] from the SDK
    // catalog. Used by [isConsumable] to route purchase finalization between
    // `consume` (consumables) and `acknowledge` (everything else). The
    // catalog is loaded post-bootstrap, so lookups may return `null` for
    // queue-drained purchases from a prior session — that's the
    // safe-default-acknowledge branch.
    private val productTypeLookup: (productId: String) -> ProductType?,
    private val onEntitlementsMayHaveChanged: () -> Unit,
    private val onPendingClaim: (PendingClaim) -> Unit,
    private val emitEvent: (ZeroSettleEvent) -> Unit,
    // Deferred-bridge hooks for [com.zerosettle.sdk.ZeroSettle.purchaseViaPlayBilling]:
    // resolve / fail the awaiting deferred from inside the listener-driven sync.
    // Defaults are no-ops so non-bridging callers (tests, future entry points)
    // keep working unchanged.
    private val onPurchaseSynced: (transactionId: String) -> Unit = {},
    private val onPurchaseFailed: (error: ZeroSettleError) -> Unit = {},
    // UCB enablement (Phase 2 Chunk B). Defaults to disabled so existing call
    // sites compile unchanged. Wiring of [UcbConfigRepository] into the
    // bootstrap pipeline lands in Chunk D — until then the SDK runs in
    // standard Play Billing mode regardless of tenant config.
    ucbConfig: UcbConfig = UcbConfig.Disabled,
    // Stripe-backed checkout launcher invoked when Google's choice screen
    // routes the user to alt billing. Chunk C replaces the default no-op
    // with `StripeCheckoutLauncher`. Only consulted when UCB is enabled in
    // non-DMA mode (the DMA path doesn't show a choice screen at all).
    ucbCheckoutLauncher: UcbCheckoutLauncher = NoopUcbCheckoutLauncher,
) {
    val queue: PlaySyncQueue = PlaySyncQueue(context.applicationContext)

    // IMPORTANT field order: [ucbChoiceHandler] must be declared before
    // [billing] because [billing]'s initializer reads it. Kotlin initializes
    // top-level `val`s in source order — reversing this would pass `null` to
    // [PlayBillingManager]'s require() check and crash construction when UCB
    // is enabled in non-DMA mode.
    private val ucbChoiceHandler: UcbChoiceHandler? =
        if (ucbConfig.isEnabled && !ucbConfig.dmaAltBillingOnlyEea) {
            UcbChoiceHandler(
                scope = scope,
                logger = logger,
                launcher = ucbCheckoutLauncher,
                userIdProvider = userIdProvider,
            )
        } else {
            null
        }

    private val billing = PlayBillingManager(
        context = context,
        obfuscatedAccountIdProvider = obfuscatedAccountIdProvider,
        onPurchases = { purchases -> scope.launch { handlePurchases(purchases) } },
        logger = logger,
        // Terminal listener failures (USER_CANCELED, item unavailable, service
        // errors, OK-with-no-purchases) flow into the SAME `onPurchaseFailed`
        // bridge the sync processor uses — resolving any in-flight
        // purchaseViaPlayBilling() deferred with Result.failure. Fixes the
        // cancel-hang where dismissing the Play sheet stranded the caller.
        onPurchaseFailed = onPurchaseFailed,
        ucbConfig = ucbConfig,
        ucbChoiceListener = ucbChoiceHandler,
    )

    private val processor = PurchaseSyncProcessor(
        backend = backend, queue = queue,
        finalize = { productId, token ->
            // ZS-diag: boundary 3 — finalize routing decision
            val resolvedType = productTypeLookup(productId)
            val consumable = isConsumable(productId, productTypeLookup)
            android.util.Log.w(
                "ZS-diag",
                "finalize: productId=$productId resolvedType=$resolvedType isConsumable=$consumable → ${if (consumable) "consume" else "acknowledge"}",
            )
            if (consumable) billing.consume(token)
            else billing.acknowledge(token)
        },
        emitEvent = emitEvent, onConflictClaim = onPendingClaim,
        onPurchaseSynced = onPurchaseSynced, onPurchaseFailed = onPurchaseFailed,
        strictAck = strictAck, logger = logger,
    )

    private val reconciler = SubscriptionReconciler(backend)

    private val connectivityManager get() = context.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Called once after bootstrap. Connects, drains the queue, reconciles, registers the network callback. */
    fun start() {
        scope.launch {
            billing.ensureConnected().onFailure { logger.warn("billing", "connect failed: ${it.message}") }
            processor.retryQueued()
            runReconcile()
        }
        registerNetworkCallback()
    }

    /** Native Play Billing purchase: query details → launch flow. The result is delivered via [handlePurchases]. */
    suspend fun purchaseViaPlayBilling(activity: Activity, product: Product): Result<Unit> {
        val playProductId = product.playProductId ?: product.id
        val billingProductType = playBillingProductType(product)
        android.util.Log.w("ZS-debug", "queryProductDetails(playProductId=$playProductId, productType=$billingProductType)")
        val detailsResult = billing.queryProductDetails(listOf(playProductId), billingProductType)
        detailsResult.onFailure { ex ->
            android.util.Log.w("ZS-debug", "queryProductDetails FAILED: ${ex.javaClass.simpleName}: ${ex.message}")
        }
        val details = detailsResult.getOrElse { return Result.failure(it) }
        android.util.Log.w("ZS-debug", "queryProductDetails returned ${details.size} item(s)")
        val match = details.firstOrNull() ?: return Result.failure(ZeroSettleError.ProductNotFound(playProductId))
        return billing.launchBillingFlow(activity, match)
    }

    private suspend fun handlePurchases(purchases: List<Purchase>) {
        val uid = userIdProvider() ?: return
        for (p in purchases) {
            processor.process(PlayBillingManager.describePurchase(p, uid, customerNameProvider(), customerEmailProvider()))
        }
        runReconcile()
        processor.retryQueued()
        onEntitlementsMayHaveChanged()
    }

    /**
     * Test-only: drive a single fake [Purchase] through [processor]. Skips the
     * surrounding `runReconcile` + `retryQueued` calls — those invoke
     * `BillingClient.queryPurchases` which goes through `ensureConnected` on a
     * client that has no real Play services in Robolectric (would hang or fail
     * loudly). The deferred-bridge wiring at [com.zerosettle.sdk.ZeroSettle]'s
     * `onPurchaseSynced` / `onPurchaseFailed` callbacks lives entirely inside
     * the `processor.process(...)` call, so the surrounding work isn't needed
     * for that contract test.
     */
    internal suspend fun processPurchaseForTesting(purchase: Purchase) {
        val uid = userIdProvider() ?: return
        processor.process(PlayBillingManager.describePurchase(purchase, uid, customerNameProvider(), customerEmailProvider()))
    }

    /**
     * Test-only: drive the [PlayBillingManager] purchases listener with a
     * synthetic billing result — exercises the cancel-hang fix end-to-end
     * through the same coordinator `configure()` built, including the
     * `onPurchaseFailed` bridge that resolves any in-flight
     * `purchaseViaPlayBilling()` deferred. Robolectric can't make a real
     * BillingClient fire the listener, so this seam stands in for it.
     */
    internal fun simulateListenerForTesting(responseCode: Int, debugMessage: String, purchases: List<Purchase>?) {
        billing.simulateListenerForTesting(responseCode, debugMessage, purchases)
    }

    private suspend fun runReconcile() {
        val uid = userIdProvider() ?: return
        val subs = billing.queryPurchases(BillingClient.ProductType.SUBS).getOrDefault(emptyList())
        val inapp = billing.queryPurchases(BillingClient.ProductType.INAPP).getOrDefault(emptyList())
        val all = (subs + inapp).map {
            ReconcilePurchase(
                purchaseToken = it.purchaseToken, productId = it.products.firstOrNull().orEmpty(),
                packageName = it.packageName, originalJson = it.originalJson, signature = it.signature,
            )
        }
        reconciler.reconcile(uid, all).onSuccess { onEntitlementsMayHaveChanged() }
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scope.launch { processor.retryQueued() } }
        }
        networkCallback = cb
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                // API 23: no registerDefaultNetworkCallback — register against an
                // internet-capable request instead (registerNetworkCallback is API 21+).
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, cb)
            }
        }
    }

    /** logout(): clear the queue, end the Billing connection, unregister the callback. */
    suspend fun shutdown() {
        queue.clear()
        networkCallback?.let { cb -> runCatching { connectivityManager?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        billing.endConnection()
    }
}
