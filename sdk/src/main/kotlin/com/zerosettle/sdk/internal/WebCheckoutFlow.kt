package com.zerosettle.sdk.internal

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.zerosettle.sdk.core.ZSLogger

/**
 * Orchestrates the web checkout flow: creates a checkout session,
 * opens it in Chrome Custom Tab or external browser, and parses deep link callbacks.
 * Maps to iOS `WebCheckoutFlow`.
 *
 * Key Android difference: Custom Tabs have no dismiss callback.
 * The SDK detects return via `onResume()` and polls `verifyTransaction`.
 */
internal class WebCheckoutFlow(
    private val backend: Backend,
) {
    companion object {
        private val CALLBACK_HOSTS = setOf("api.zerosettle.io", "landing.zerosettle.ngrok.app")
        private const val CALLBACK_PATH_PREFIX = "/checkout/callback"

        /** Custom URI scheme for Android deep link callbacks (no server-side verification needed). */
        private const val CUSTOM_SCHEME = "zerosettle"
        private const val CUSTOM_SCHEME_HOST = "checkout"
        private const val CUSTOM_SCHEME_PATH = "/callback"
    }

    /**
     * Create a checkout session and open the checkout URL in the appropriate browser.
     * Returns the checkout session for transaction polling.
     */
    suspend fun beginCheckout(
        activity: Activity,
        productId: String,
        userId: String? = null,
        checkoutType: com.zerosettle.sdk.model.CheckoutType,
    ): CheckoutSession {
        ZSLogger.info("Creating checkout session for product: $productId", ZSLogger.Category.IAP)

        val session = backend.createCheckoutSession(
            productId = productId,
            userId = userId,
        )

        ZSLogger.info(
            "Checkout session created, transaction: ${session.transactionId ?: "none"}",
            ZSLogger.Category.IAP
        )

        when (checkoutType) {
            com.zerosettle.sdk.model.CheckoutType.EXTERNAL_BROWSER -> {
                ZSLogger.debug("Opening checkout URL in external browser", ZSLogger.Category.IAP)
                openInExternalBrowser(activity, session.checkoutUrl)
            }
            com.zerosettle.sdk.model.CheckoutType.CUSTOM_TAB -> {
                ZSLogger.debug("Opening checkout URL in Chrome Custom Tab", ZSLogger.Category.IAP)
                openInCustomTab(activity, session.checkoutUrl)
            }
            com.zerosettle.sdk.model.CheckoutType.WEB_VIEW,
            com.zerosettle.sdk.model.CheckoutType.NATIVE_PAY -> {
                ZSLogger.debug(
                    "WebView checkout — session created but not opening browser",
                    ZSLogger.Category.IAP
                )
            }
        }

        return session
    }

    /**
     * Parse a deep link callback URL from the checkout flow.
     * Accepts both HTTPS App Links (`https://api.zerosettle.io/checkout/callback`)
     * and the custom scheme (`zerosettle://checkout/callback`).
     * Returns parsed callback data, or null if the URL is not a ZeroSettle checkout callback.
     */
    fun handleCallback(uri: Uri): CheckoutCallback? {
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val path = uri.path ?: ""

        val isCustomScheme = scheme == CUSTOM_SCHEME && host == CUSTOM_SCHEME_HOST &&
                path.startsWith(CUSTOM_SCHEME_PATH)
        val isHttpsCallback = scheme == "https" && host in CALLBACK_HOSTS &&
                path.startsWith(CALLBACK_PATH_PREFIX)

        if (!isCustomScheme && !isHttpsCallback) {
            ZSLogger.error("Unable to handle callback due to invalid components: $uri")
            return null
        }

        val transactionId = uri.getQueryParameter("transaction_id")
        val productId = uri.getQueryParameter("product_id")
        val status = uri.getQueryParameter("status")

        if (transactionId == null || productId == null || status == null) {
            ZSLogger.error(
                "Checkout callback missing required parameters: $uri",
                ZSLogger.Category.IAP
            )
            return null
        }

        val success = status == "success"
        ZSLogger.info(
            "Checkout callback received: transaction=$transactionId, status=$status",
            ZSLogger.Category.IAP
        )

        return CheckoutCallback(
            transactionId = transactionId,
            productId = productId,
            success = success,
        )
    }

    private fun openInExternalBrowser(activity: Activity, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        activity.startActivity(intent)
    }

    private fun openInCustomTab(activity: Activity, url: String) {
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.launchUrl(activity, Uri.parse(url))
    }
}

/**
 * Parsed data from a deep link checkout callback.
 */
internal data class CheckoutCallback(
    val transactionId: String,
    val productId: String,
    val success: Boolean,
)
