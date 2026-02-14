package com.zerosettle.sample

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.ZeroSettleDelegate
import com.zerosettle.sdk.model.Entitlement
import com.zerosettle.sdk.model.ZSProduct
import com.zerosettle.sdk.model.ZSTransaction

class SampleAppState : ZeroSettleDelegate {

    var userId by mutableStateOf("sample_user_123")
    var products = mutableStateListOf<ZSProduct>()
    var entitlements = mutableStateListOf<Entitlement>()
    var isLoadingProducts by mutableStateOf(false)
    var productError by mutableStateOf<String?>(null)
    var statusMessage by mutableStateOf<String?>(null)

    var currentEnvironment by mutableStateOf(IAPEnvironment.SANDBOX)
        private set

    // These reference the SDK singletons but need activity context for some calls
    var activity: Activity? = null
    private var appContext: Context? = null

    /** Call once from Activity.onCreate to load persisted environment. */
    fun initEnvironment(context: Context) {
        appContext = context.applicationContext
        currentEnvironment = IAPEnvironment.load(context)
    }

    /** Switch environment, persist, reconfigure SDK, and re-fetch products. */
    suspend fun switchEnvironment(env: IAPEnvironment) {
        val ctx = appContext ?: return
        currentEnvironment = env
        IAPEnvironment.save(ctx, env)

        // Reconfigure SDK
        ZeroSettle.baseUrlOverride = env.baseUrlOverride
        ZeroSettle.configure(
            context = ctx,
            config = ZeroSettle.Configuration(
                publishableKey = env.publishableKey,
                syncPlayStoreTransactions = true,
            ),
        )
        ZeroSettle.delegate = this

        // Re-bootstrap (products + entitlements)
        products.clear()
        entitlements.clear()
        statusMessage = "Switched to ${env.displayName}"
        bootstrap()
    }

    val checkoutTypeName: String
        get() = try { ZeroSettle.checkoutType.name } catch (_: Exception) { "N/A" }

    val jurisdictionName: String?
        get() = try { ZeroSettle.detectedJurisdiction.value?.name } catch (_: Exception) { null }

    val isWebCheckoutEnabled: Boolean
        get() = try { ZeroSettle.isWebCheckoutEnabled } catch (_: Exception) { false }

    suspend fun fetchProducts() {
        isLoadingProducts = true
        productError = null
        try {
            val catalog = ZeroSettle.fetchProducts(userId = userId)
            products.clear()
            products.addAll(catalog.products)
            isLoadingProducts = false
        } catch (e: Exception) {
            productError = e.message
            isLoadingProducts = false
        }
    }

    suspend fun bootstrap() {
        isLoadingProducts = true
        productError = null
        try {
            val catalog = ZeroSettle.bootstrap(userId = userId)
            products.clear()
            products.addAll(catalog.products)
            isLoadingProducts = false
        } catch (e: Exception) {
            productError = e.message
            isLoadingProducts = false
        }
    }

    suspend fun restoreEntitlements() {
        val result = ZeroSettle.restoreEntitlements(userId = userId)
        entitlements.clear()
        entitlements.addAll(result)
    }

    suspend fun purchaseViaWeb(productId: String) {
        val act = activity ?: throw IllegalStateException("No activity")
        val transaction = ZeroSettle.purchase(
            activity = act,
            productId = productId,
            userId = userId,
        )
        statusMessage = "Purchase complete: ${transaction.id}"
    }

    suspend fun purchaseViaPlayStore(productId: String) {
        val act = activity ?: throw IllegalStateException("No activity")
        ZeroSettle.purchaseViaPlayStore(
            activity = act,
            productId = productId,
            userId = userId,
        )
        statusMessage = "Play Store purchase complete!"
    }

    suspend fun openCustomerPortal() {
        val act = activity ?: throw IllegalStateException("No activity")
        ZeroSettle.openCustomerPortal(activity = act, userId = userId)
    }

    suspend fun manageSubscription() {
        val act = activity ?: throw IllegalStateException("No activity")
        ZeroSettle.showManageSubscription(activity = act, userId = userId)
    }

    // -- ZeroSettleDelegate --

    override fun zeroSettleCheckoutDidBegin(productId: String) {
        statusMessage = "Checkout started: $productId"
    }

    override fun zeroSettleCheckoutDidComplete(transaction: ZSTransaction) {
        statusMessage = "Checkout complete: ${transaction.id}"
    }

    override fun zeroSettleCheckoutDidCancel(productId: String) {
        statusMessage = "Checkout cancelled: $productId"
    }

    override fun zeroSettleCheckoutDidFail(productId: String, error: Throwable) {
        statusMessage = "Checkout failed: ${error.message}"
    }

    override fun zeroSettleEntitlementsDidUpdate(entitlements: List<Entitlement>) {
        this.entitlements.clear()
        this.entitlements.addAll(entitlements)
    }

    override fun zeroSettleDidSyncPlayStoreTransaction(productId: String, purchaseToken: String) {
        statusMessage = "Play Store synced: $productId"
    }

    override fun zeroSettlePlayStoreSyncFailed(error: Throwable) {
        statusMessage = "Play Store sync failed: ${error.message}"
    }
}
