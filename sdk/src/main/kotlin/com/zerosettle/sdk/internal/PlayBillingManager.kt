package com.zerosettle.sdk.internal

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.zerosettle.sdk.core.ZSLogger
import com.zerosettle.sdk.model.Entitlement
import com.zerosettle.sdk.model.EntitlementSource
import kotlinx.coroutines.*
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Internal delegate for receiving Play Billing sync events.
 * Maps to iOS `StoreKitUpdateDelegate`.
 */
internal interface PlayBillingUpdateDelegate {
    fun playBillingDidSyncTransaction(productId: String, purchaseToken: String)
    fun playBillingSyncFailed(error: Throwable)
    fun playBillingEntitlementsDidChange(entitlements: List<Entitlement>)
}

/**
 * Errors that can occur during Play Billing purchases.
 * Maps to iOS `StoreKitPurchaseError`.
 */
sealed class PlayBillingPurchaseError : Exception() {
    data class ProductNotFound(val productId: String) : PlayBillingPurchaseError() {
        override val message: String get() = "Product not found: $productId"
    }

    data object UserCancelled : PlayBillingPurchaseError() {
        override val message: String get() = "Purchase was cancelled"
    }

    data object Pending : PlayBillingPurchaseError() {
        override val message: String get() = "Purchase is pending approval"
    }

    data class BillingError(val responseCode: Int) : PlayBillingPurchaseError() {
        override val message: String get() = "Billing error: $responseCode"
    }
}

/**
 * Wraps the Google Play Billing Library.
 * Maps to iOS `StoreKitManager`.
 */
internal class PlayBillingManager(
    context: Context,
    private val backend: Backend,
) : PurchasesUpdatedListener {

    var delegate: PlayBillingUpdateDelegate? = null
    var currentUserId: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("DEPRECATION")
    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    private var isConnected = false

    // -- Connection Lifecycle --

    suspend fun startConnection() {
        if (isConnected) return

        suspendCancellableCoroutine { continuation ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        isConnected = true
                        ZSLogger.info("Play Billing connected", ZSLogger.Category.IAP)
                        continuation.resume(Unit)
                    } else {
                        ZSLogger.error(
                            "Play Billing setup failed: ${result.responseCode}",
                            ZSLogger.Category.IAP
                        )
                        continuation.resumeWithException(
                            PlayBillingPurchaseError.BillingError(result.responseCode)
                        )
                    }
                }

                override fun onBillingServiceDisconnected() {
                    isConnected = false
                    ZSLogger.warn("Play Billing disconnected", ZSLogger.Category.IAP)
                }
            })
        }
    }

    fun endConnection() {
        billingClient.endConnection()
        isConnected = false
        scope.cancel()
        ZSLogger.info("Play Billing connection ended", ZSLogger.Category.IAP)
    }

    fun setUserId(userId: String) {
        this.currentUserId = userId
    }

    // -- Product Fetching --

    /**
     * Fetch Play Store products for reconciliation with ZeroSettle catalog.
     */
    suspend fun fetchProducts(productIds: List<String>): Map<String, ProductDetails> {
        if (productIds.isEmpty()) return emptyMap()
        ensureConnected()

        ZSLogger.info(
            "Requesting ${productIds.size} products from Play Store: $productIds",
            ZSLogger.Category.IAP
        )

        val result = mutableMapOf<String, ProductDetails>()

        // Query in-app products
        val inAppParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                }
            )
            .build()

        val inAppResult = withContext(Dispatchers.IO) {
            billingClient.queryProductDetails(inAppParams)
        }

        inAppResult.productDetailsList?.forEach { details ->
            result[details.productId] = details
        }

        // Query subscription products
        val subParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                }
            )
            .build()

        val subResult = withContext(Dispatchers.IO) {
            billingClient.queryProductDetails(subParams)
        }

        subResult.productDetailsList?.forEach { details ->
            result[details.productId] = details
        }

        val missing = productIds.toSet() - result.keys
        if (missing.isNotEmpty()) {
            ZSLogger.info(
                "Play Store did NOT find these product IDs: $missing",
                ZSLogger.Category.IAP
            )
        }

        ZSLogger.info(
            "Fetched ${result.size}/${productIds.size} Play Store products",
            ZSLogger.Category.IAP
        )

        return result
    }

    // -- Purchasing --

    /**
     * Launch the Play Billing purchase flow.
     */
    suspend fun purchase(activity: Activity, productDetails: ProductDetails): Purchase {
        ensureConnected()

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .apply {
                    // For subscriptions, select the first offer
                    productDetails.subscriptionOfferDetails?.firstOrNull()?.let {
                        setOfferToken(it.offerToken)
                    }
                }
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            throw PlayBillingPurchaseError.BillingError(result.responseCode)
        }

        // The actual result comes via onPurchasesUpdated callback
        return suspendCancellableCoroutine { continuation ->
            pendingPurchaseContinuation = continuation
        }
    }

    // Continuation for the purchase flow
    private var pendingPurchaseContinuation:
        kotlinx.coroutines.CancellableContinuation<Purchase>? = null

    // -- PurchasesUpdatedListener --

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        val continuation = pendingPurchaseContinuation
        pendingPurchaseContinuation = null

        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchase = purchases?.firstOrNull()
                if (purchase != null) {
                    // Acknowledge and sync in background
                    handlePurchase(purchase)
                    continuation?.resume(purchase)
                } else {
                    continuation?.resumeWithException(
                        PlayBillingPurchaseError.BillingError(billingResult.responseCode)
                    )
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                continuation?.resumeWithException(PlayBillingPurchaseError.UserCancelled)
            }

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Re-query existing purchases
                val purchase = purchases?.firstOrNull()
                if (purchase != null) {
                    continuation?.resume(purchase)
                } else {
                    continuation?.resumeWithException(
                        PlayBillingPurchaseError.BillingError(billingResult.responseCode)
                    )
                }
            }

            else -> {
                continuation?.resumeWithException(
                    PlayBillingPurchaseError.BillingError(billingResult.responseCode)
                )
            }
        }
    }

    // -- Current Entitlements --

    /**
     * Get current entitlements from Play Store active purchases.
     */
    suspend fun getCurrentEntitlements(): List<Entitlement> {
        ensureConnected()
        val entitlements = mutableListOf<Entitlement>()

        // Query in-app purchases
        val inAppResult = withContext(Dispatchers.IO) {
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )
        }
        inAppResult.purchasesList.forEach { purchase ->
            entitlements.addAll(entitlementsFromPurchase(purchase))
        }

        // Query subscriptions
        val subResult = withContext(Dispatchers.IO) {
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
        }
        subResult.purchasesList.forEach { purchase ->
            entitlements.addAll(entitlementsFromPurchase(purchase))
        }

        return entitlements
    }

    // -- Private --

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        // Acknowledge if needed
        if (!purchase.isAcknowledged) {
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(ackParams) { result ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    ZSLogger.info(
                        "Purchase acknowledged: ${purchase.products}",
                        ZSLogger.Category.IAP
                    )
                } else {
                    ZSLogger.error(
                        "Failed to acknowledge purchase: ${result.responseCode}",
                        ZSLogger.Category.IAP
                    )
                }
            }
        }

        // Sync token to backend
        val userId = currentUserId ?: run {
            ZSLogger.debug("No userId set, skipping Play Store sync", ZSLogger.Category.IAP)
            return
        }

        val productId = purchase.products.firstOrNull() ?: return

        scope.launch {
            try {
                backend.syncPlayStoreTransaction(
                    purchaseToken = purchase.purchaseToken,
                    userId = userId,
                )
                ZSLogger.info("Play Store transaction synced: $productId", ZSLogger.Category.IAP)
                delegate?.playBillingDidSyncTransaction(productId, purchase.purchaseToken)
            } catch (e: Exception) {
                ZSLogger.error("Failed to sync Play Store transaction: $e", ZSLogger.Category.IAP)
                delegate?.playBillingSyncFailed(e)
            }

            // Refresh entitlements after a new transaction
            try {
                val entitlements = getCurrentEntitlements()
                delegate?.playBillingEntitlementsDidChange(entitlements)
            } catch (e: Exception) {
                ZSLogger.error("Failed to refresh entitlements: $e", ZSLogger.Category.IAP)
            }
        }
    }

    private fun entitlementsFromPurchase(purchase: Purchase): List<Entitlement> {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return emptyList()

        return purchase.products.map { productId ->
            Entitlement(
                id = "play_${purchase.orderId ?: purchase.purchaseToken.take(16)}",
                productId = productId,
                source = EntitlementSource.PLAY_STORE,
                isActive = true,
                expiresAt = null,
                purchasedAt = Instant.ofEpochMilli(purchase.purchaseTime).toString(),
            )
        }
    }

    private suspend fun ensureConnected() {
        if (!isConnected) {
            startConnection()
        }
    }
}
