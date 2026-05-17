package com.zerosettle.sdk.checkout

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
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
 * **Presentation.** Rendered as a bottom-anchored sheet to match iOS Kit's
 * "WebView Sheet (WKWebView)" UX: ~88% screen height, rounded top corners,
 * slide-up on enter, slide-down + scrim fade on exit, tap-outside-the-sheet
 * dismisses as cancel. The animation runs on the inner views (not the
 * window) so the scrim can fade independently of the sheet's translateY —
 * a window-level animation would slide them together, which doesn't match.
 *
 * Intercepts `zerosettle://checkout/return?…` callbacks from the WebView
 * (same scheme the Custom Tab path uses) and routes them through
 * [ZeroSettle.completeWebCheckout] — the existing deferred-bridge in
 * [ZeroSettle.purchase] then resolves the same way it would for a Custom
 * Tab return.
 *
 * Back press / close button / tap-outside / (future) drag-down all funnel
 * through [handleCancel] which synthesizes a cancel callback so the
 * pending checkout's `await` is completed exceptionally with
 * [com.zerosettle.sdk.models.ZeroSettleError.PurchaseCancelled], never
 * left hanging.
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
    private lateinit var rootScrim: FrameLayout
    private lateinit var sheetContainer: LinearLayout
    private var isDismissing: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val checkoutUrl = intent.getStringExtra(EXTRA_CHECKOUT_URL)
        if (checkoutUrl.isNullOrBlank()) {
            // Defensive: should never happen since we control the launch path.
            // Fail closed by completing the SDK's deferred with cancel + finish.
            // No animation here — bail straight out before the sheet ever paints.
            handleCancelImmediate()
            return
        }

        setContentView(buildLayout())
        wireBackPressToCancel()
        configureWebView()
        webView.loadUrl(checkoutUrl)
        playEnterAnimation()
    }

    private fun buildLayout(): View {
        // Layout:
        //   [outer FrameLayout = scrim — tap dismisses]
        //     [inner LinearLayout = sheet, anchored bottom, ~88% height,
        //      background = white rounded-top drawable]
        //       [FrameLayout sheetHeader, 44dp tall, transparent]
        //         [View dragHandle (36dp × 4dp grey pill, top-centered)]
        //         [ImageButton closeButton (end-anchored, vertically centered)]
        //       [FrameLayout sheetBody, weight=1]
        //         [WebView (fills)]
        //         [ProgressBar centered]
        //
        // The header sits ABOVE the WebView so the sheet's white rounded-top
        // drawable shows around the drag handle / close X — the WebView no
        // longer occupies the rounded-corner region, so its inner page CSS
        // can't paint over the corners. No clipToOutline / outline provider
        // needed; the structural split is the fix.
        //
        // The scrim's background starts transparent; playEnterAnimation()
        // fades it to a translucent black while the sheet slides up. No
        // window-level animations / windowAnimationStyle — animating the
        // window slides scrim + sheet together, which looks wrong vs iOS.
        val density = resources.displayMetrics.density

        rootScrim = FrameLayout(this).apply {
            setBackgroundColor(SCRIM_TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setOnClickListener { handleCancel() }
        }

        sheetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R_DRAWABLE_ZS_SHEET_BACKGROUND)
            // Consume taps inside the sheet so they don't propagate to the
            // scrim's dismiss-on-click listener.
            isClickable = true
            isFocusable = true
        }

        // ── Sheet header: drag handle pill + close X ─────────────────────
        // Transparent background so the sheet's white rounded-top drawable
        // shows through at the corners.
        val sheetHeader = FrameLayout(this)

        val dragHandle = View(this).apply {
            setBackgroundResource(R_DRAWABLE_ZS_SHEET_DRAG_HANDLE)
        }
        val dragHandleWidthPx = (DRAG_HANDLE_WIDTH_DP * density).toInt()
        val dragHandleHeightPx = (DRAG_HANDLE_HEIGHT_DP * density).toInt()
        val dragHandleTopMarginPx = (DRAG_HANDLE_TOP_MARGIN_DP * density).toInt()
        sheetHeader.addView(
            dragHandle,
            FrameLayout.LayoutParams(dragHandleWidthPx, dragHandleHeightPx).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dragHandleTopMarginPx
            },
        )

        val closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setOnClickListener { handleCancel() }
            contentDescription = "Close checkout"
        }
        val closeSizePx = (CLOSE_BUTTON_SIZE_DP * density).toInt()
        val closeEndMarginPx = (CLOSE_BUTTON_END_MARGIN_DP * density).toInt()
        sheetHeader.addView(
            closeButton,
            FrameLayout.LayoutParams(closeSizePx, closeSizePx).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = closeEndMarginPx
            },
        )

        val sheetHeaderHeightPx = (SHEET_HEADER_HEIGHT_DP * density).toInt()
        sheetContainer.addView(
            sheetHeader,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                sheetHeaderHeightPx,
            ),
        )

        // ── Sheet body: WebView + centered progress spinner ──────────────
        val sheetBody = FrameLayout(this)

        webView = WebView(this)
        sheetBody.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        progressBar = ProgressBar(this).apply { isIndeterminate = true }
        sheetBody.addView(
            progressBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )

        sheetContainer.addView(
            sheetBody,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        // Sheet height = ~88% of screen height (leaves the status bar +
        // small gap visible above), wraps when content is smaller.
        val sheetHeightPx = (resources.displayMetrics.heightPixels * SHEET_HEIGHT_RATIO).toInt()
        val sheetParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            sheetHeightPx,
        ).apply { gravity = Gravity.BOTTOM }
        rootScrim.addView(sheetContainer, sheetParams)
        return rootScrim
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

        // Expose `window.ZeroSettleAndroid` to the checkout page. Its mere
        // presence flips the page's `inNativeWebView` detection (see
        // templates/checkout.html:920-924) so the page renders the
        // bottom-sheet-optimized native branch — the lean header
        // adopters customize via CheckoutBranding — instead of the
        // full-page browser fallback meant for desktop-Safari users.
        //
        // The bridge also receives structured messages from the page
        // via [CheckoutJsBridge.onNativeMessage] (Android equivalent of
        // iOS's `window.webkit.messageHandlers.*` pattern). Today this
        // channel is informational only — the canonical success/cancel
        // pathway is the deep-link URL interception in the
        // WebViewClient below.
        @Suppress("AddJavascriptInterface") // First-party page, controlled origin.
        webView.addJavascriptInterface(CheckoutJsBridge(this), JS_BRIDGE_NAME)

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

    /**
     * Runs the slide-up + scrim-fade animation once the sheet is laid out.
     * We defer to a pre-draw listener so the sheet's measured height is
     * available — otherwise translationY = height would be zero on the
     * first pass and there'd be no slide-up.
     */
    private fun playEnterAnimation() {
        if (!::sheetContainer.isInitialized) return
        val sheet = sheetContainer
        val scrim = rootScrim
        // Hide initially; pre-draw listener fires once layout is measured,
        // sets translationY to off-screen, then animates back to 0.
        sheet.visibility = View.INVISIBLE
        sheet.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sheet.viewTreeObserver.removeOnPreDrawListener(this)
                sheet.translationY = sheet.height.toFloat()
                sheet.visibility = View.VISIBLE
                sheet.animate()
                    .translationY(0f)
                    .setDuration(ANIM_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                fadeScrim(from = 0, to = SCRIM_DIM_ALPHA, durationMs = ANIM_DURATION_MS)
                return true
            }
        })
    }

    /**
     * Visual-only counterpart to [playEnterAnimation]: kick off a slide-down +
     * scrim-fade so the dismiss looks animated. Does NOT call [finish] — the
     * caller (handleCancel / handleCallback / the overridden [finish]) is
     * responsible for invoking [super.finish] synchronously alongside this
     * so `isFinishing` flips immediately and the SDK's deferred resolves.
     * The window-close ordinarily runs before the animation completes; this
     * is a deliberate trade for synchronous semantics so callers don't have
     * to thread a callback through every dismiss surface.
     */
    private fun playExitAnimation() {
        if (isDismissing) return
        isDismissing = true
        if (!::sheetContainer.isInitialized) return
        val sheet = sheetContainer
        sheet.animate()
            .translationY(sheet.height.toFloat())
            .setDuration(ANIM_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .start()
        fadeScrim(from = SCRIM_DIM_ALPHA, to = 0, durationMs = ANIM_DURATION_MS)
    }

    private fun fadeScrim(from: Int, to: Int, durationMs: Long) {
        if (!::rootScrim.isInitialized) return
        ValueAnimator.ofArgb(scrimColor(from), scrimColor(to)).apply {
            duration = durationMs
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                rootScrim.setBackgroundColor(color)
            }
            start()
        }
    }

    private fun scrimColor(alpha: Int): Int = Color.argb(alpha, 0, 0, 0)

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

    /**
     * Cancel path for the defensive missing-extra branch where no sheet
     * has been laid out yet. Skips animation entirely and finishes the
     * activity directly. `overridePendingTransition` is deprecated in API
     * 34+ in favor of `overrideActivityTransition`, but it's still the
     * lowest-cost call that suppresses the default window transition; the
     * deprecation is informational, not functional. Re-evaluate when the
     * SDK's minSdk crosses 34.
     */
    @Suppress("DEPRECATION")
    private fun handleCancelImmediate() {
        val cancelUrl = Uri.Builder()
            .scheme(WebCheckoutFlow.CALLBACK_SCHEME)
            .authority(WebCheckoutFlow.CALLBACK_HOST)
            .path(WebCheckoutFlow.CALLBACK_PATH)
            .appendQueryParameter("status", "cancelled")
            .build()
            .toString()
        completeOnGlobalScope(cancelUrl)
        super.finish()
        overridePendingTransition(0, 0)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun completeOnGlobalScope(callbackUrl: String) {
        // See class docstring for why we deliberately escape lifecycleScope.
        GlobalScope.launch { ZeroSettle.completeWebCheckout(callbackUrl) }
    }

    override fun finish() {
        // Kick off the slide-down + scrim-fade animation alongside the
        // synchronous super.finish(). The activity transition + window
        // teardown often outruns the animation; that's intentional. We
        // optimize for synchronous `isFinishing` semantics (the SDK's
        // deferred bridge depends on the activity reporting finished
        // promptly) over a perfectly choreographed exit. The visible
        // result on a real device is still a slide-down because the
        // system window-anim composer renders the activity frame buffer
        // through the dismiss.
        playExitAnimation()
        super.finish()
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

    /**
     * JS bridge exposed to the checkout page as `window.ZeroSettleAndroid`.
     *
     * Two purposes:
     *   1. Its mere presence flips the page's `inNativeWebView` detection
     *      (templates/checkout.html:920-924) so the page renders its
     *      native branch (bottom-sheet-optimized layout with the lean
     *      header adopters customize via CheckoutBranding on the
     *      dashboard) instead of the full-page browser fallback.
     *   2. Receives structured messages from the page via
     *      [onNativeMessage]. The page posts these for richer UX
     *      signals like `ready`, `buttonsReady`, `error`, `complete`
     *      — the Android equivalent of iOS's
     *      `window.webkit.messageHandlers.*` pattern.
     *
     * Success / cancel routing still flows through the deep-link
     * callback URL interception (`zerosettle://checkout/return?…`) —
     * that's the canonical path. The native-message channel is
     * additive, primarily used today to signal `buttonsReady` so the
     * SDK doesn't block waiting for payment-buttons-ready when there's
     * no wallet available.
     */
    internal class CheckoutJsBridge(
        @Suppress("unused") private val activity: ZeroSettleWebViewActivity,
    ) {
        /**
         * Called from the page on its native-message channel. Payload
         * is a JSON string; we parse defensively and never throw — a
         * malformed payload must not crash the WebView render thread.
         *
         * **Threading.** `@JavascriptInterface` methods fire on a
         * private WebView worker thread, NOT the main thread. Today we
         * only log so no UI hop is needed; any future routing back into
         * the activity must `activity.runOnUiThread { … }`.
         */
        @JavascriptInterface
        public fun onNativeMessage(payload: String) {
            val action = runCatching {
                org.json.JSONObject(payload).optString("action", "")
            }.getOrDefault("")
            android.util.Log.i(
                "ZeroSettleAndroid",
                "onNativeMessage action=$action payload=${payload.take(MAX_LOG_PAYLOAD_CHARS)}",
            )
            // Future:
            //   action == "ready"        → could dismiss our own loading spinner
            //   action == "buttonsReady" → analytics breadcrumb
            //   action == "error"        → could surface as Result.failure via the
            //                              pending deferred (today the URL
            //                              interception handles terminal states;
            //                              the JS error channel is informational)
            //   action == "complete"     → ignored; URL interception is canonical
        }

        private companion object {
            private const val MAX_LOG_PAYLOAD_CHARS = 500
        }
    }

    public companion object {
        internal const val EXTRA_CHECKOUT_URL: String = "zs_checkout_url"

        /**
         * Name the [CheckoutJsBridge] is exposed under on the JavaScript
         * global. Must stay in sync with the page's detection at
         * `templates/checkout.html:920-924` (`window.ZeroSettleAndroid`)
         * and the `postToNative` Android branch at
         * `templates/checkout.html:2263-2267`.
         */
        internal const val JS_BRIDGE_NAME: String = "ZeroSettleAndroid"

        private const val MATCH_PARENT = FrameLayout.LayoutParams.MATCH_PARENT
        private const val SHEET_HEIGHT_RATIO = 0.88f
        private const val ANIM_DURATION_MS = 260L
        private const val SCRIM_DIM_ALPHA = 0x99 // ~60% black scrim
        private const val SCRIM_TRANSPARENT = 0x00000000

        // Sheet header (drag handle + close button) dimensions. The header
        // bar sits above the WebView so the sheet's white rounded-top
        // drawable shows around the drag handle / close X — matches iOS
        // Kit's SwiftUI .sheet UX. Tweak SHEET_HEADER_HEIGHT_DP if the
        // close button feels cramped; the header steals from the WebView's
        // weight=1 region so SHEET_HEIGHT_RATIO does not need to change.
        private const val SHEET_HEADER_HEIGHT_DP = 44
        private const val DRAG_HANDLE_WIDTH_DP = 36
        private const val DRAG_HANDLE_HEIGHT_DP = 4
        private const val DRAG_HANDLE_TOP_MARGIN_DP = 8
        private const val CLOSE_BUTTON_SIZE_DP = 36
        private const val CLOSE_BUTTON_END_MARGIN_DP = 8

        // Resolved at compile time via R class. Kept as a constant ref so the
        // (rare) test that exercises buildLayout() with a mocked resources
        // surface stays grep-able.
        private val R_DRAWABLE_ZS_SHEET_BACKGROUND: Int
            get() = com.zerosettle.sdk.R.drawable.zs_sheet_background

        private val R_DRAWABLE_ZS_SHEET_DRAG_HANDLE: Int
            get() = com.zerosettle.sdk.R.drawable.zs_sheet_drag_handle

        /** Build the launch [Intent] for this activity. Visible for [WebCheckoutFlow.launchWebView]. */
        public fun newIntent(activity: Activity, checkoutUrl: String): Intent =
            Intent(activity, ZeroSettleWebViewActivity::class.java).apply {
                putExtra(EXTRA_CHECKOUT_URL, checkoutUrl)
            }
    }
}
