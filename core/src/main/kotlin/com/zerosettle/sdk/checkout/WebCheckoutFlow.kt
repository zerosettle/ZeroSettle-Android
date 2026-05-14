package com.zerosettle.sdk.checkout

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Launches the Stripe web checkout URL and recognises the deep-link the checkout
 * page redirects to on completion.
 *
 * The checkout page returns to `zerosettle://checkout/return?status=…` — the host
 * app must register an `intent-filter` for that scheme on the Activity that should
 * receive the result (the `:sample` app and docs show the manifest snippet). The
 * SDK parses the redirect via [parseCallback]; nothing here touches the network.
 */
public object WebCheckoutFlow {

    public const val CALLBACK_SCHEME: String = "zerosettle"
    public const val CALLBACK_HOST: String = "checkout"
    public const val CALLBACK_PATH: String = "/return"

    /** Result of the checkout page's redirect back into the app. */
    public sealed class CallbackResult {
        public data class Succeeded(val transactionId: String?) : CallbackResult()
        public data object Cancelled : CallbackResult()
        public data class Failed(val reason: String) : CallbackResult()
    }

    /** True when [url] is the `zerosettle://checkout/return…` callback. */
    public fun isCallbackUrl(url: String): Boolean = runCatching {
        val u = Uri.parse(url)
        u.scheme == CALLBACK_SCHEME && u.host == CALLBACK_HOST
    }.getOrDefault(false)

    /**
     * Parse a redirect URL. Returns null when [url] is not a checkout callback so the
     * caller can ignore unrelated navigations (e.g., Stripe's own intra-flow URLs).
     */
    public fun parseCallback(url: String): CallbackResult? {
        if (!isCallbackUrl(url)) return null
        val u = Uri.parse(url)
        return when (u.getQueryParameter("status")) {
            "success" -> CallbackResult.Succeeded(transactionId = u.getQueryParameter("transaction_id"))
            "cancelled", "canceled" -> CallbackResult.Cancelled
            "failed" -> CallbackResult.Failed(reason = u.getQueryParameter("reason") ?: "unknown")
            else -> CallbackResult.Failed(reason = "unrecognized_status")
        }
    }

    /** Open [checkoutUrl] in a Chrome Custom Tab (default presentation for the headless core). */
    public fun launchCustomTab(activity: Activity, checkoutUrl: String) {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, Uri.parse(checkoutUrl))
    }

    /** Open [checkoutUrl] in the user's default browser. */
    public fun launchExternalBrowser(activity: Activity, checkoutUrl: String) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(checkoutUrl)))
    }

    /**
     * Open [checkoutUrl] in an in-app WebView ([ZeroSettleWebViewActivity]) — the
     * presentation honored when the merchant's dashboard selected `inline` or
     * `sheet`. Adopters using the `:ui` Compose module typically get a richer
     * presentation from `ZeroSettleCheckoutSheet`; this activity is for headless
     * adopters (incl. Flutter, vanilla Views, plain Activities) who can't take
     * a Compose dependency.
     */
    public fun launchWebView(activity: Activity, checkoutUrl: String) {
        activity.startActivity(ZeroSettleWebViewActivity.newIntent(activity, checkoutUrl))
    }
}
