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
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    private val onEntitlementsMayHaveChanged: () -> Unit,
    private val onPendingClaim: (PendingClaim) -> Unit,
    private val emitEvent: (ZeroSettleEvent) -> Unit,
    // Deferred-bridge hooks for [com.zerosettle.sdk.ZeroSettle.purchaseViaPlayBilling]:
    // resolve / fail the awaiting deferred from inside the listener-driven sync.
    // Defaults are no-ops so non-bridging callers (tests, future entry points)
    // keep working unchanged.
    private val onPurchaseSynced: (transactionId: String) -> Unit = {},
    private val onPurchaseFailed: (error: ZeroSettleError) -> Unit = {},
) {
    val queue: PlaySyncQueue = PlaySyncQueue(context.applicationContext)

    private val billing = PlayBillingManager(
        context = context,
        obfuscatedAccountIdProvider = obfuscatedAccountIdProvider,
        onPurchases = { purchases -> scope.launch { handlePurchases(purchases) } },
        logger = logger,
    )

    private val processor = PurchaseSyncProcessor(
        backend = backend, queue = queue,
        acknowledge = { token -> billing.acknowledge(token) },
        emitEvent = emitEvent, onConflictClaim = onPendingClaim,
        onPurchaseSynced = onPurchaseSynced, onPurchaseFailed = onPurchaseFailed,
        strictAck = strictAck,
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
        val details = billing.queryProductDetails(listOf(playProductId)).getOrElse { return Result.failure(it) }
            .firstOrNull() ?: return Result.failure(ZeroSettleError.ProductNotFound(playProductId))
        return billing.launchBillingFlow(activity, details)
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
