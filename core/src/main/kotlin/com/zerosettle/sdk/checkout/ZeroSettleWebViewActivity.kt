package com.zerosettle.sdk.checkout

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Hosts the ZeroSettle web checkout in an in-app WebView for adopters who
 * configured `INLINE` or `SHEET` presentation in the dashboard. Headless
 * adopters that don't take the `:ui` Compose module get WebView support
 * from this activity without any per-adopter setup — the activity is
 * registered in `core/src/main/AndroidManifest.xml`.
 *
 * Intercepts `zerosettle://checkout/return?…` callbacks from the WebView
 * (same scheme the Custom Tab path uses) and routes them through
 * [ZeroSettle.completeWebCheckout] — the existing deferred-bridge in
 * [ZeroSettle.purchase] then resolves the same way it would for a Custom
 * Tab return.
 *
 * Back press / close button → finishes after synthesizing a cancel
 * callback so the pending checkout's `await` is completed exceptionally
 * with [com.zerosettle.sdk.models.ZeroSettleError.PurchaseCancelled],
 * never left hanging.
 *
 * **Scope choice.** [ZeroSettle.completeWebCheckout] is `suspend` and does
 * a network `restoreEntitlements()` call on the success branch. We launch
 * it on [GlobalScope] (not `lifecycleScope`) because the activity calls
 * [finish] in the same step — `lifecycleScope` would cancel mid-flight
 * and the SDK's entitlement refresh + event emission would never run.
 * The SDK singleton outlives the activity, so a process-scoped launch is
 * the correct lifetime here.
 */
public class ZeroSettleWebViewActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val checkoutUrl = intent.getStringExtra(EXTRA_CHECKOUT_URL)
        if (checkoutUrl.isNullOrBlank()) {
            // Defensive: should never happen since we control the launch path.
            // Fail closed by completing the SDK's deferred with cancel + finish.
            handleCancel()
            return
        }

        setContentView(buildLayout())
        wireBackPressToCancel()
        configureWebView()
        webView.loadUrl(checkoutUrl)
    }

    private fun buildLayout(): View {
        // Programmatic layout — no XML — so this activity has zero resource
        // dependencies on the host app. Layout:
        //   [WebView fills the entire container]
        //   [ProgressBar centered, hidden once the page finishes loading]
        //   [Close X button overlaying top-right, 48dp tap target]
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        progressBar = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(
            progressBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )

        val density = resources.displayMetrics.density
        val closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setOnClickListener { handleCancel() }
            contentDescription = "Close checkout"
        }
        val size = (48 * density).toInt()
        val margin = (8 * density).toInt()
        root.addView(
            closeButton,
            FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, margin, margin, 0)
            },
        )
        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The checkout page uses Stripe.js + may need third-party cookies
            // for Link / wallet auth. Adopters who need stricter sandboxing
            // can opt for the Custom Tab presentation instead.
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }

        // Enable the W3C Payment Request API inside the WebView. Without this
        // the Stripe ExpressCheckoutElement's `canMakePayment` probe returns
        // `{googlePay: false}` even on devices with Google Pay set up, and the
        // checkout page falls through to its "Set up Google Pay to continue"
        // wallet-setup CTA — for wallets-only PMCs that's a blank-CTA dead
        // end. Vanilla android.webkit.WebView disables Payment Request by
        // default; androidx.webkit 1.14.0+ exposes a Compat shim to opt in.
        // The intent <queries> required for the system to route the PAY
        // intent to Google Pay live in core/src/main/AndroidManifest.xml.
        // Reference:
        // https://developers.google.com/pay/api/android/guides/recipes/using-android-webview
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PAYMENT_REQUEST)) {
            WebSettingsCompat.setPaymentRequestEnabled(webView.settings, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (WebCheckoutFlow.isCallbackUrl(url.toString())) {
                    handleCallback(url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun wireBackPressToCancel() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    handleCancel()
                }
            }
        })
    }

    private fun handleCallback(uri: Uri) {
        // Hand off to the SDK's existing deep-link entry point so the
        // deferred bridge resolves identically to the Custom Tab path.
        // Don't try to interpret success/failure here — that's
        // completeWebCheckout's job.
        completeOnGlobalScope(uri.toString())
        finish()
    }

    private fun handleCancel() {
        // Synthesize a cancel callback URL so the SDK's existing completion
        // logic treats this exactly like a Custom Tab cancel return. Routes
        // through WebCheckoutFlow.parseCallback → CallbackResult.Cancelled →
        // resolves the deferred exceptionally with PurchaseCancelled.
        val cancelUrl = Uri.Builder()
            .scheme(WebCheckoutFlow.CALLBACK_SCHEME)
            .authority(WebCheckoutFlow.CALLBACK_HOST)
            .path(WebCheckoutFlow.CALLBACK_PATH)
            .appendQueryParameter("status", "cancelled")
            .build()
            .toString()
        completeOnGlobalScope(cancelUrl)
        finish()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun completeOnGlobalScope(callbackUrl: String) {
        // See class docstring for why we deliberately escape lifecycleScope.
        GlobalScope.launch { ZeroSettle.completeWebCheckout(callbackUrl) }
    }

    override fun onDestroy() {
        // Free WebView resources eagerly — Activity teardown alone leaves
        // the page in the back/forward cache.
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    public companion object {
        internal const val EXTRA_CHECKOUT_URL: String = "zs_checkout_url"
        private const val MATCH_PARENT = FrameLayout.LayoutParams.MATCH_PARENT

        /** Build the launch [Intent] for this activity. Visible for [WebCheckoutFlow.launchWebView]. */
        public fun newIntent(activity: Activity, checkoutUrl: String): Intent =
            Intent(activity, ZeroSettleWebViewActivity::class.java).apply {
                putExtra(EXTRA_CHECKOUT_URL, checkoutUrl)
            }
    }
}
