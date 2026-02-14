package com.zerosettle.sdk

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.telephony.TelephonyManager
import com.zerosettle.sdk.core.HttpError
import com.zerosettle.sdk.core.ZSLogger
import com.zerosettle.sdk.error.*
import com.zerosettle.sdk.internal.*
import com.zerosettle.sdk.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Main entry point for the ZeroSettle IAP SDK.
 * Handles web checkout, entitlement management, and Play Store transaction syncing.
 * Maps to iOS `ZeroSettle` (singleton).
 */
object ZeroSettle : PlayBillingUpdateDelegate {

    // -- Configuration --

    data class Configuration(
        /** Your publishable key from the ZeroSettle dashboard (e.g., "zs_pk_live_abc123"). */
        val publishableKey: String,
        /** Whether to listen for and forward native Play Store transactions to ZeroSettle. */
        val syncPlayStoreTransactions: Boolean = true,
    ) {
        internal val backendUrl: String get() = "https://api.zerosettle.io/v1"
    }

    // -- Published State (StateFlow replaces @Published) --

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    private val _products = MutableStateFlow<List<ZSProduct>>(emptyList())
    val products: StateFlow<List<ZSProduct>> = _products.asStateFlow()

    private val _entitlements = MutableStateFlow<List<Entitlement>>(emptyList())
    val entitlements: StateFlow<List<Entitlement>> = _entitlements.asStateFlow()

    private val _pendingCheckout = MutableStateFlow(false)
    val pendingCheckout: StateFlow<Boolean> = _pendingCheckout.asStateFlow()

    private val _remoteConfig = MutableStateFlow<RemoteConfig?>(null)
    val remoteConfig: StateFlow<RemoteConfig?> = _remoteConfig.asStateFlow()

    private val _detectedJurisdiction = MutableStateFlow<Jurisdiction?>(null)
    val detectedJurisdiction: StateFlow<Jurisdiction?> = _detectedJurisdiction.asStateFlow()

    // -- Async Observation (SharedFlow replaces AsyncStream) --

    private val _entitlementUpdates = MutableSharedFlow<List<Entitlement>>(replay = 1)
    val entitlementUpdates: SharedFlow<List<Entitlement>> = _entitlementUpdates.asSharedFlow()

    // -- Computed Properties --

    /**
     * The effective checkout type for the detected jurisdiction.
     */
    val checkoutType: CheckoutType
        get() {
            val config = _remoteConfig.value?.checkout ?: return CheckoutType.EXTERNAL_BROWSER
            val jurisdiction = _detectedJurisdiction.value ?: Jurisdiction.ROW
            val override = config.jurisdictions[jurisdiction]
            return override?.sheetType ?: config.sheetType
        }

    /**
     * Whether web checkout is enabled for the detected jurisdiction.
     */
    val isWebCheckoutEnabled: Boolean
        get() {
            val config = _remoteConfig.value?.checkout ?: return true
            val jurisdiction = _detectedJurisdiction.value ?: Jurisdiction.ROW
            val override = config.jurisdictions[jurisdiction]
            return override?.isEnabled ?: config.isEnabled
        }

    // -- Delegate --

    var delegate: ZeroSettleDelegate? = null

    // -- Debug --

    /** Override the backend base URL for local development. Only use in debug builds. */
    var baseUrlOverride: String? = null

    // -- Internal State --

    internal var currentConfig: Configuration? = null
        private set

    internal val effectiveBaseUrl: String?
        get() {
            val config = currentConfig ?: return null
            return baseUrlOverride ?: config.backendUrl
        }

    private var backend: Backend? = null
    private var checkoutFlow: WebCheckoutFlow? = null
    private var customerPortalFlow: CustomerPortalFlow? = null
    private var playBillingManager: PlayBillingManager? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Used by ZSPaymentSheetActivity to deliver results back to the suspend purchase() call. */
    internal var paymentSheetDeferred: CompletableDeferred<ZSTransaction>? = null

    // -- Configuration --

    /**
     * Configure the SDK. Must be called before any other methods.
     * Requires a [Context] for Play Billing initialization.
     */
    fun configure(context: Context, config: Configuration) {
        this.appContext = context.applicationContext
        this.currentConfig = config

        val baseUrl = baseUrlOverride ?: config.backendUrl
        val backend = Backend(baseUrl = baseUrl, publishableKey = config.publishableKey)
        this.backend = backend

        this.checkoutFlow = WebCheckoutFlow(backend)
        this.customerPortalFlow = CustomerPortalFlow()

        if (config.syncPlayStoreTransactions) {
            val manager = PlayBillingManager(context.applicationContext, backend)
            manager.delegate = this
            this.playBillingManager = manager

            scope.launch {
                try {
                    manager.startConnection()
                } catch (e: Exception) {
                    ZSLogger.error("Failed to start Play Billing: $e", ZSLogger.Category.IAP)
                }
            }
        }

        _isConfigured.value = true
        ZSLogger.info("ZeroSettle configured", ZSLogger.Category.IAP)
    }

    // -- Bootstrap --

    /**
     * Convenience that fetches products and restores entitlements.
     */
    suspend fun bootstrap(userId: String): ProductCatalog {
        val catalog = fetchProducts(userId = userId)
        restoreEntitlements(userId = userId)
        return catalog
    }

    // -- Products --

    /**
     * Fetch the product catalog from ZeroSettle with web checkout pricing.
     * Also reconciles with Play Store products for native purchasing support.
     */
    suspend fun fetchProducts(userId: String? = null): ProductCatalog {
        val backend = this.backend ?: throw ZSError.NotConfigured

        try {
            val catalog = backend.fetchProducts(userId = userId)
            var fetchedProducts = catalog.products.toMutableList()
            ZSLogger.info(
                "Fetched ${fetchedProducts.size} products from backend",
                ZSLogger.Category.IAP
            )

            // Store remote config
            catalog.config?.let { config ->
                _remoteConfig.value = config
                ZSLogger.info(
                    "Remote config received: checkoutType=${config.checkout.sheetType}, jurisdictions=${config.checkout.jurisdictions.size}, migration=${config.migration != null}",
                    ZSLogger.Category.IAP
                )
            }

            // Detect jurisdiction
            detectJurisdiction()

            // Reconcile with Play Store products (non-fatal — degrades gracefully)
            val allProductIds = fetchedProducts.map { it.id }
            val manager = playBillingManager
            if (manager != null && allProductIds.isNotEmpty()) {
                try {
                    val playProducts = manager.fetchProducts(allProductIds)

                    for (i in fetchedProducts.indices) {
                        playProducts[fetchedProducts[i].id]?.let { playProduct ->
                            fetchedProducts[i] = fetchedProducts[i].copy().also {
                                it.playStoreProduct = playProduct
                            }
                        }
                    }

                    val matched = fetchedProducts.count { it.playStoreAvailable }
                    ZSLogger.info(
                        "Reconciled $matched/${fetchedProducts.size} products with Play Store",
                        ZSLogger.Category.IAP
                    )
                } catch (e: Exception) {
                    ZSLogger.warn(
                        "Play Store reconciliation skipped: ${e.message}",
                        ZSLogger.Category.IAP
                    )
                }
            }

            _products.value = fetchedProducts
            return ProductCatalog(products = fetchedProducts, config = catalog.config)
        } catch (e: Exception) {
            ZSLogger.error("Failed to fetch products: $e", ZSLogger.Category.IAP)
            throw Backend.wrapError(e)
        }
    }

    // -- Purchase (Web Checkout) --

    /**
     * Initiate a web checkout for the given product.
     * Opens a Stripe checkout page in the configured checkout type (Custom Tab,
     * external browser, or WebView).
     *
     * @param activity Required for launching Custom Tab / browser
     * @param productId The product identifier to purchase
     * @param userId Your app's user identifier. Required for subscriptions and non-consumables.
     */
    suspend fun purchase(
        activity: Activity,
        productId: String,
        userId: String? = null,
    ): ZSTransaction {
        val checkoutFlow = this.checkoutFlow ?: throw ZSError.NotConfigured
        val backend = this.backend ?: throw ZSError.NotConfigured

        // Subscriptions and non-consumables require a userId
        if (userId == null) {
            val product = _products.value.firstOrNull { it.id == productId }
            if (product != null && (product.type == ZSProductType.AUTO_RENEWABLE_SUBSCRIPTION ||
                    product.type == ZSProductType.NON_RENEWING_SUBSCRIPTION ||
                    product.type == ZSProductType.NON_CONSUMABLE)
            ) {
                throw ZSError.UserIdRequired(productId)
            }
        }

        // Check jurisdiction
        if (!isWebCheckoutEnabled) {
            val jurisdiction = _detectedJurisdiction.value ?: Jurisdiction.ROW
            throw ZSError.WebCheckoutDisabledForJurisdiction(jurisdiction)
        }

        // Update Play Billing manager with user ID
        if (userId != null) {
            playBillingManager?.setUserId(userId)
        }

        // Signal checkout started
        _pendingCheckout.value = true
        delegate?.zeroSettleCheckoutDidBegin(productId = productId)

        try {
            // WEBVIEW path: launch ZSPaymentSheetActivity and await its result
            if (checkoutType == CheckoutType.WEBVIEW) {
                return purchaseViaPaymentSheet(activity, backend, productId, userId)
            }

            // CUSTOM_TAB / EXTERNAL_BROWSER path: open browser, poll/deep link
            val session = checkoutFlow.beginCheckout(
                activity = activity,
                productId = productId,
                userId = userId,
                checkoutType = checkoutType,
            )

            ZSLogger.info(
                "Checkout browser opened for $productId, transaction: ${session.transactionId ?: "none"}",
                ZSLogger.Category.IAP
            )

            // If deep link callback already fired, pendingCheckout is false
            if (!_pendingCheckout.value) {
                ZSLogger.debug("Callback already processed via deep link", ZSLogger.Category.IAP)
                session.transactionId?.let { txnId ->
                    return backend.getTransaction(txnId)
                }
                throw ZSError.Cancelled
            }

            // Deep link didn't fire — verify via polling
            val transactionId = session.transactionId
            if (transactionId == null) {
                _pendingCheckout.value = false
                delegate?.zeroSettleCheckoutDidCancel(productId = productId)
                throw ZSError.Cancelled
            }

            try {
                val transaction = backend.verifyTransaction(transactionId = transactionId)
                _pendingCheckout.value = false
                ZSLogger.info(
                    "Transaction $transactionId verified via polling",
                    ZSLogger.Category.IAP
                )
                delegate?.zeroSettleCheckoutDidComplete(transaction = transaction)
                refreshEntitlementsAfterCheckout(transaction)
                return transaction
            } catch (sheetError: PaymentSheetError) {
                _pendingCheckout.value = false
                when (sheetError) {
                    is PaymentSheetError.Cancelled -> {
                        delegate?.zeroSettleCheckoutDidCancel(productId = productId)
                        throw ZSError.Cancelled
                    }
                    is PaymentSheetError.VerificationFailed -> {
                        delegate?.zeroSettleCheckoutDidFail(productId, sheetError)
                        throw ZSError.TransactionVerificationFailed(sheetError.detail)
                    }
                    else -> {
                        delegate?.zeroSettleCheckoutDidFail(productId, sheetError)
                        throw ZSError.CheckoutFailed(
                            CheckoutFailure.Other(sheetError.message ?: "Unknown error")
                        )
                    }
                }
            }
        } catch (e: ZSError) {
            _pendingCheckout.value = false
            throw e
        } catch (e: Exception) {
            _pendingCheckout.value = false
            ZSLogger.error("Checkout failed for $productId: ${e.message}", ZSLogger.Category.IAP)
            delegate?.zeroSettleCheckoutDidFail(productId, e)

            val reason = classifyCheckoutFailure(e)
            throw ZSError.CheckoutFailed(reason)
        }
    }

    /**
     * WEBVIEW checkout: creates a checkout session, launches ZSPaymentSheetActivity,
     * and suspends until the activity delivers a result via [paymentSheetDeferred].
     */
    private suspend fun purchaseViaPaymentSheet(
        activity: Activity,
        backend: Backend,
        productId: String,
        userId: String?,
    ): ZSTransaction {
        val paymentIntent = backend.createPaymentIntent(
            productId = productId,
            userId = userId,
            freeTrialDays = 0,
        )

        ZSLogger.info(
            "Payment sheet: intent created, transaction=${paymentIntent.transactionId}, launching WebView",
            ZSLogger.Category.IAP
        )

        val deferred = CompletableDeferred<ZSTransaction>()
        paymentSheetDeferred = deferred

        val intent = com.zerosettle.sdk.ui.ZSPaymentSheetActivity.createIntent(
            context = activity,
            productId = productId,
            userId = userId,
            checkoutUrl = paymentIntent.checkoutUrl,
            transactionId = paymentIntent.transactionId,
        )
        activity.startActivity(intent)

        try {
            val transaction = deferred.await()
            _pendingCheckout.value = false
            delegate?.zeroSettleCheckoutDidComplete(transaction = transaction)
            refreshEntitlementsAfterCheckout(transaction)
            return transaction
        } catch (e: PaymentSheetError.Cancelled) {
            _pendingCheckout.value = false
            delegate?.zeroSettleCheckoutDidCancel(productId = productId)
            throw ZSError.Cancelled
        } catch (e: PaymentSheetError) {
            _pendingCheckout.value = false
            delegate?.zeroSettleCheckoutDidFail(productId, e)
            throw ZSError.CheckoutFailed(
                CheckoutFailure.Other(e.message ?: "Payment sheet error")
            )
        } catch (e: Exception) {
            _pendingCheckout.value = false
            delegate?.zeroSettleCheckoutDidFail(productId, e)
            throw Backend.wrapError(e)
        } finally {
            paymentSheetDeferred = null
        }
    }

    // -- Purchase via Play Store --

    /**
     * Purchase a product via Google Play Billing.
     * Use this for products synced to Play Console where [ZSProduct.playStoreAvailable] is true.
     */
    suspend fun purchaseViaPlayStore(
        activity: Activity,
        productId: String,
        userId: String? = null,
    ) {
        val manager = playBillingManager ?: throw ZSError.NotConfigured

        val product = _products.value.firstOrNull { it.id == productId }
            ?: throw ZSError.ProductNotFound(productId)

        if (userId == null && (product.type == ZSProductType.AUTO_RENEWABLE_SUBSCRIPTION ||
                product.type == ZSProductType.NON_RENEWING_SUBSCRIPTION ||
                product.type == ZSProductType.NON_CONSUMABLE)
        ) {
            throw ZSError.UserIdRequired(productId)
        }

        val playProduct = product.playStoreProduct
            ?: throw PlayBillingPurchaseError.ProductNotFound(productId)

        if (userId != null) {
            manager.setUserId(userId)
        }
        manager.purchase(activity, playProduct)
    }

    // -- Migration Tracking --

    /**
     * Track a successful migration conversion.
     */
    suspend fun trackMigrationConversion(userId: String) {
        val backend = this.backend ?: throw ZSError.NotConfigured
        try {
            backend.trackMigrationConversion(userId = userId)
            ZSLogger.info("Migration conversion tracked for user: $userId", ZSLogger.Category.IAP)
        } catch (e: Exception) {
            ZSLogger.error("Failed to track migration conversion: $e", ZSLogger.Category.IAP)
            throw Backend.wrapError(e)
        }
    }

    // -- Customer Portal --

    /**
     * Open the Stripe customer portal for subscription management.
     */
    suspend fun openCustomerPortal(activity: Activity, userId: String) {
        val backend = this.backend ?: throw ZSError.NotConfigured
        val portalFlow = customerPortalFlow ?: throw ZSError.NotConfigured

        try {
            val session = backend.createCustomerPortalSession(userId = userId)
            ZSLogger.info("Customer portal session created", ZSLogger.Category.IAP)
            portalFlow.presentPortal(activity, session.portalUrl)
        } catch (e: Exception) {
            ZSLogger.error("Customer portal failed: $e", ZSLogger.Category.IAP)
            throw Backend.wrapError(e)
        }
    }

    /**
     * Smart subscription management that routes to the appropriate UI.
     * - Web checkout or both sources → Opens Stripe customer portal
     * - Play Store only → Opens Play Store subscription management
     */
    suspend fun showManageSubscription(activity: Activity, userId: String) {
        val hasWebEntitlements = _entitlements.value.any { it.source == EntitlementSource.WEB_CHECKOUT }
        val hasPlayStoreEntitlements = _entitlements.value.any { it.source == EntitlementSource.PLAY_STORE }

        if (hasPlayStoreEntitlements && !hasWebEntitlements) {
            ZSLogger.info(
                "Opening Play Store subscription management (Play Store-only entitlements)",
                ZSLogger.Category.IAP
            )
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/account/subscriptions")
            )
            activity.startActivity(intent)
            try {
                restoreEntitlements(userId = userId)
            } catch (_: Exception) {}
        } else {
            ZSLogger.info(
                "Opening Stripe customer portal (web/mixed/no entitlements)",
                ZSLogger.Category.IAP
            )
            openCustomerPortal(activity, userId)
        }
    }

    // -- Deep Link Handling --

    /**
     * Handle a deep link callback from the web checkout.
     * Call this from your Activity's `onNewIntent()` or deep link handler.
     *
     * @return true if the URL was handled by ZeroSettle, false otherwise
     */
    fun handleDeepLink(uri: Uri): Boolean {
        ZSLogger.info("Handling deep link redirect", ZSLogger.Category.IAP)

        val flow = checkoutFlow ?: run {
            ZSLogger.error("handleDeepLink called but SDK not configured", ZSLogger.Category.IAP)
            return false
        }

        val callback = flow.handleCallback(uri) ?: return false

        scope.launch {
            processCheckoutCallback(callback)
        }

        return true
    }

    // -- onResume --

    /**
     * SDK lifecycle hook for pending checkout detection.
     * Call this from your Activity's `onResume()`.
     * On Android, Custom Tabs have no dismiss callback, so we detect return
     * via onResume and poll for transaction verification.
     */
    fun onResume() {
        if (!_pendingCheckout.value) return
        ZSLogger.debug("onResume with pending checkout — will verify on next poll", ZSLogger.Category.IAP)
    }

    // -- Entitlements --

    /**
     * Restore entitlements from both ZeroSettle backend and Play Store.
     */
    suspend fun restoreEntitlements(userId: String): List<Entitlement> {
        ZSLogger.info("[restoreEntitlements] Called with userId=\"$userId\"", ZSLogger.Category.IAP)

        val backend = this.backend ?: run {
            ZSLogger.error(
                "[restoreEntitlements] SDK not configured, throwing notConfigured",
                ZSLogger.Category.IAP
            )
            throw ZSError.NotConfigured
        }

        playBillingManager?.setUserId(userId)

        val allEntitlements = mutableListOf<Entitlement>()

        // Fetch Play Store entitlements first
        val manager = playBillingManager
        if (manager != null) {
            try {
                val playStoreEntitlements = manager.getCurrentEntitlements()
                allEntitlements.addAll(playStoreEntitlements)
                ZSLogger.info(
                    "[restoreEntitlements] Restored ${playStoreEntitlements.size} Play Store entitlements",
                    ZSLogger.Category.IAP
                )
            } catch (e: Exception) {
                ZSLogger.error("[restoreEntitlements] Failed to get Play Store entitlements: $e", ZSLogger.Category.IAP)
            }
        }

        // Fetch web checkout entitlements from ZeroSettle backend
        try {
            ZSLogger.info(
                "[restoreEntitlements] Fetching web entitlements from backend...",
                ZSLogger.Category.IAP
            )
            val webEntitlements = backend.getEntitlements(userId = userId)
            allEntitlements.addAll(webEntitlements)
            ZSLogger.info(
                "[restoreEntitlements] Restored ${webEntitlements.size} web entitlements",
                ZSLogger.Category.IAP
            )
        } catch (e: Exception) {
            ZSLogger.error("[restoreEntitlements] Failed to fetch web entitlements: $e", ZSLogger.Category.IAP)
            updateEntitlements(allEntitlements)
            throw ZSError.RestoreEntitlementsFailed(
                partialEntitlements = allEntitlements,
                underlyingError = e,
            )
        }

        updateEntitlements(allEntitlements)
        return allEntitlements
    }

    // -- PlayBillingUpdateDelegate --

    override fun playBillingDidSyncTransaction(productId: String, purchaseToken: String) {
        delegate?.zeroSettleDidSyncPlayStoreTransaction(productId, purchaseToken)
    }

    override fun playBillingSyncFailed(error: Throwable) {
        delegate?.zeroSettlePlayStoreSyncFailed(error)
    }

    override fun playBillingEntitlementsDidChange(entitlements: List<Entitlement>) {
        val webEntitlements = _entitlements.value.filter { it.source == EntitlementSource.WEB_CHECKOUT }
        updateEntitlements(webEntitlements + entitlements)
    }

    // -- Private Helpers --

    private fun updateEntitlements(newEntitlements: List<Entitlement>) {
        _entitlements.value = newEntitlements
        scope.launch {
            _entitlementUpdates.emit(newEntitlements)
        }
        delegate?.zeroSettleEntitlementsDidUpdate(newEntitlements)
    }

    private fun detectJurisdiction() {
        val context = appContext ?: return

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val countryCode = telephonyManager?.simCountryIso?.uppercase()
            ?: java.util.Locale.getDefault().country.uppercase()

        if (countryCode.isNotEmpty()) {
            val jurisdiction = Jurisdiction.from(countryCode)
            _detectedJurisdiction.value = jurisdiction
            ZSLogger.info(
                "Detected jurisdiction: ${jurisdiction.name} (country: $countryCode)",
                ZSLogger.Category.IAP
            )
        } else {
            _detectedJurisdiction.value = Jurisdiction.ROW
            ZSLogger.info(
                "Country unavailable, defaulting to ROW jurisdiction",
                ZSLogger.Category.IAP
            )
        }
    }

    private suspend fun processCheckoutCallback(callback: CheckoutCallback) {
        _pendingCheckout.value = false

        if (!callback.success) {
            ZSLogger.info(
                "Checkout cancelled for product: ${callback.productId}",
                ZSLogger.Category.IAP
            )
            delegate?.zeroSettleCheckoutDidCancel(productId = callback.productId)
            return
        }

        val backend = this.backend ?: return

        try {
            val transaction = backend.getTransaction(transactionId = callback.transactionId)

            if (transaction.status != TransactionStatus.COMPLETED &&
                transaction.status != TransactionStatus.PROCESSING
            ) {
                val error = ZSError.TransactionVerificationFailed(
                    "Transaction status: ${transaction.status.name.lowercase()}"
                )
                delegate?.zeroSettleCheckoutDidFail(callback.productId, error)
                return
            }

            ZSLogger.info(
                "Checkout ${if (transaction.status == TransactionStatus.PROCESSING) "processing" else "completed"}: ${transaction.id} for ${transaction.productId}",
                ZSLogger.Category.IAP
            )
            delegate?.zeroSettleCheckoutDidComplete(transaction = transaction)
            refreshEntitlementsAfterCheckout(transaction)
        } catch (e: Exception) {
            ZSLogger.error("Transaction verification failed: $e", ZSLogger.Category.IAP)
            delegate?.zeroSettleCheckoutDidFail(
                callback.productId,
                ZSError.TransactionVerificationFailed(e.message ?: "Unknown error")
            )
        }
    }

    private suspend fun refreshEntitlementsAfterCheckout(transaction: ZSTransaction) {
        val backend = this.backend ?: return

        val userId = playBillingManager?.currentUserId
        if (userId != null) {
            try {
                val freshEntitlements = backend.getEntitlements(userId = userId)
                val playStoreEnts = _entitlements.value.filter { it.source == EntitlementSource.PLAY_STORE }
                updateEntitlements(playStoreEnts + freshEntitlements)
                ZSLogger.info(
                    "Refreshed entitlements after checkout: ${freshEntitlements.size} web entitlements",
                    ZSLogger.Category.IAP
                )
            } catch (e: Exception) {
                ZSLogger.error("Failed to refresh entitlements after checkout: $e", ZSLogger.Category.IAP)
                appendLocalEntitlement(transaction)
            }
        } else {
            appendLocalEntitlement(transaction)
        }
    }

    private fun appendLocalEntitlement(transaction: ZSTransaction) {
        // Only create local entitlements for transactions that actually completed
        if (transaction.status != TransactionStatus.COMPLETED &&
            transaction.status != TransactionStatus.PROCESSING
        ) {
            ZSLogger.warn(
                "Skipping local entitlement for transaction ${transaction.id} with status ${transaction.status}",
                ZSLogger.Category.IAP
            )
            return
        }

        val entitlement = Entitlement(
            id = "web_${transaction.id}",
            productId = transaction.productId,
            source = EntitlementSource.WEB_CHECKOUT,
            isActive = true,
            expiresAt = transaction.expiresAt,
            purchasedAt = transaction.purchasedAt,
        )
        val updated = _entitlements.value.toMutableList()
        updated.add(entitlement)
        updateEntitlements(updated)
    }

    private fun classifyCheckoutFailure(error: Throwable): CheckoutFailure {
        if (error !is HttpError) return CheckoutFailure.Other(error.message ?: "Unknown error")

        return when (error) {
            is HttpError.HttpErrorResponse -> {
                val parsed = parseServerBody(error.body)
                when (parsed.code) {
                    "product_not_found" -> CheckoutFailure.ProductNotFound
                    "merchant_not_onboarded" -> CheckoutFailure.MerchantNotOnboarded
                    else -> {
                        if (parsed.code?.startsWith("stripe_") == true) {
                            CheckoutFailure.StripeError(
                                code = parsed.code,
                                message = parsed.message ?: "Payment failed"
                            )
                        } else {
                            CheckoutFailure.ServerError(
                                statusCode = error.statusCode,
                                message = parsed.message
                            )
                        }
                    }
                }
            }
            is HttpError.NetworkError -> CheckoutFailure.NetworkUnavailable
            else -> CheckoutFailure.Other(error.message ?: "Unknown error")
        }
    }

    private data class ServerErrorBody(val code: String?, val message: String?)

    private fun parseServerBody(body: ByteArray?): ServerErrorBody {
        if (body == null) return ServerErrorBody(null, null)
        return try {
            val jsonStr = String(body)
            val jsonObj = Json.parseToJsonElement(jsonStr)
            if (jsonObj is JsonObject) {
                val code = jsonObj["code"]?.jsonPrimitive?.content
                val message = jsonObj["error"]?.jsonPrimitive?.content
                    ?: jsonObj["message"]?.jsonPrimitive?.content
                    ?: jsonObj["detail"]?.jsonPrimitive?.content
                ServerErrorBody(code, message)
            } else {
                ServerErrorBody(null, null)
            }
        } catch (_: Exception) {
            ServerErrorBody(null, null)
        }
    }
}
