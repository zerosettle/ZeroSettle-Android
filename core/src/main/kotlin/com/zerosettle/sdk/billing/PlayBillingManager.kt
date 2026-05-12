package com.zerosettle.sdk.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.zerosettle.sdk.core.ZeroSettleLogger
import com.zerosettle.sdk.models.ZeroSettleError
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns the [BillingClient]: connection lifecycle (with auto-reconnect),
 * `queryProductDetails`, `launchBillingFlow` (with the deterministic
 * `obfuscatedAccountId`), the [PurchasesUpdatedListener], `acknowledgePurchase`,
 * and `queryPurchasesAsync` (used by [SubscriptionReconciler]).
 *
 * Purchases observed via the listener (and via reconcile) are handed to
 * [onPurchases]; the SDK wires that to [PurchaseSyncProcessor]. Acknowledgement is
 * NOT done here automatically — the processor calls [acknowledge] only after backend
 * confirmation (3-day-window rule, see [PurchaseSyncProcessor]).
 */
public class PlayBillingManager(
    context: Context,
    private val obfuscatedAccountIdProvider: () -> String?,
    private val onPurchases: (List<Purchase>) -> Unit,
    private val logger: ZeroSettleLogger,
) {
    private val app = context.applicationContext

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            onPurchases(purchases)
        } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            logger.warn("billing", "PurchasesUpdated error ${result.responseCode}: ${result.debugMessage}")
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(app)
        .setListener(purchasesListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    /**
     * Connect (idempotent). Suspends until the connection is established or fails.
     *
     * PBL 7.x has no `enableAutoServiceReconnection()` (added in 8.0), so every
     * `BillingClient` call goes through [ensureConnected] which re-establishes the
     * connection if `isReady` is false — covering the disconnect-then-call path.
     */
    public suspend fun ensureConnected(): Result<Unit> = suspendCancellableCoroutine { cont ->
        if (client.isReady) { cont.resume(Result.success(Unit)); return@suspendCancellableCoroutine }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) cont.resume(Result.success(Unit))
                else cont.resume(Result.failure(mapBillingError(result.responseCode, result.debugMessage)))
            }
            override fun onBillingServiceDisconnected() { /* next call re-runs ensureConnected() */ }
        })
    }

    public suspend fun queryProductDetails(
        productIds: List<String>,
        productType: String = BillingClient.ProductType.SUBS,
    ): Result<List<ProductDetails>> {
        ensureConnected().getOrElse { return Result.failure(it) }
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            productIds.map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(productType).build()
            },
        ).build()
        val res = client.queryProductDetails(params)
        return if (res.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Result.success(res.productDetailsList ?: emptyList())
        } else {
            Result.failure(mapBillingError(res.billingResult.responseCode, res.billingResult.debugMessage))
        }
    }

    /** Launch the purchase flow for [details] (using its first base-plan offer if a subscription). */
    public suspend fun launchBillingFlow(activity: Activity, details: ProductDetails): Result<Unit> {
        ensureConnected().getOrElse { return Result.failure(it) }
        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        details.subscriptionOfferDetails?.firstOrNull()?.let {
            productParamsBuilder.setOfferToken(it.offerToken)
        }
        val flowParamsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))
        obfuscatedAccountIdProvider()?.let { flowParamsBuilder.setObfuscatedAccountId(it) }
        val result = client.launchBillingFlow(activity, flowParamsBuilder.build())
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) Result.success(Unit)
        else Result.failure(mapBillingError(result.responseCode, result.debugMessage))
    }

    public suspend fun acknowledge(purchaseToken: String): Result<Unit> {
        ensureConnected().getOrElse { return Result.failure(it) }
        val result = client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build(),
        )
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) Result.success(Unit)
        else Result.failure(mapBillingError(result.responseCode, result.debugMessage))
    }

    /** Query all current purchases of [productType] (SUBS / INAPP). Used by the reconciler. */
    public suspend fun queryPurchases(productType: String): Result<List<Purchase>> {
        ensureConnected().getOrElse { return Result.failure(it) }
        val res = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(productType).build())
        return if (res.billingResult.responseCode == BillingClient.BillingResponseCode.OK) Result.success(res.purchasesList)
        else Result.failure(mapBillingError(res.billingResult.responseCode, res.billingResult.debugMessage))
    }

    public fun endConnection() { client.endConnection() }

    public companion object {
        public fun mapBillingError(responseCode: Int, debugMessage: String): ZeroSettleError = when (responseCode) {
            BillingClient.BillingResponseCode.USER_CANCELED -> ZeroSettleError.PurchaseCancelled
            else -> ZeroSettleError.PlayBillingError(responseCode = responseCode, debugMessage = debugMessage)
        }

        /** Build a storefront-agnostic [PurchaseDescriptor] from a Play [Purchase]. */
        public fun describePurchase(
            purchase: Purchase,
            userId: String,
            customerName: String?,
            customerEmail: String?,
        ): PurchaseDescriptor = PurchaseDescriptor(
            purchaseToken = purchase.purchaseToken,
            productId = purchase.products.firstOrNull().orEmpty(),
            packageName = purchase.packageName,
            userId = userId,
            orderId = purchase.orderId,
            purchaseState = purchase.purchaseState,
            isAcknowledged = purchase.isAcknowledged,
            signature = purchase.signature,
            originalJson = purchase.originalJson,
            willAutoRenew = purchase.isAutoRenewing,
            customerName = customerName,
            customerEmail = customerEmail,
        )
    }
}
