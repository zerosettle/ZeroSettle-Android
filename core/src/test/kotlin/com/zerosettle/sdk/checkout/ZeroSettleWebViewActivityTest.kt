package com.zerosettle.sdk.checkout

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Unit tests for [ZeroSettleWebViewActivity].
 *
 * We exercise the contract the activity owns directly — URL load, callback
 * interception, defensive cancel, back-press behaviour, intent factory, AND
 * the bottom-sheet dismiss surfaces (scrim click + close button) introduced
 * when the activity moved from full-screen to sheet presentation.
 * The SDK-side bridge resolution ([com.zerosettle.sdk.ZeroSettle.completeWebCheckout])
 * is covered by `ZeroSettleWebPurchaseTest`; here we just assert that the
 * activity calls into it through the [WebCheckoutFlow.parseCallback]-compatible
 * URL contract by inspecting `WebCheckoutFlow.isCallbackUrl` on the URLs the
 * activity hands off.
 */
@RunWith(RobolectricTestRunner::class)
class ZeroSettleWebViewActivityTest {

    private fun launch(extras: Intent.() -> Unit = {}): ActivityController<ZeroSettleWebViewActivity> {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            ZeroSettleWebViewActivity::class.java,
        ).apply(extras)
        return Robolectric.buildActivity(ZeroSettleWebViewActivity::class.java, intent).create()
    }

    private fun ZeroSettleWebViewActivity.webViewField(): WebView? {
        // Reach into the private lateinit `webView` so we can drive the
        // WebViewClient without going through the real Robolectric WebView
        // shadows' navigation machinery.
        val f = ZeroSettleWebViewActivity::class.java.getDeclaredField("webView")
        f.isAccessible = true
        return f.get(this) as? WebView
    }

    private fun ZeroSettleWebViewActivity.scrimRootField(): FrameLayout? {
        val f = ZeroSettleWebViewActivity::class.java.getDeclaredField("rootScrim")
        f.isAccessible = true
        return f.get(this) as? FrameLayout
    }

    private fun ZeroSettleWebViewActivity.sheetContainerField(): LinearLayout? {
        val f = ZeroSettleWebViewActivity::class.java.getDeclaredField("sheetContainer")
        f.isAccessible = true
        return f.get(this) as? LinearLayout
    }

    private fun WebView.client(): WebViewClient = shadowOf(this).webViewClient

    @Test
    fun missingExtra_finishesImmediately() {
        // No EXTRA_CHECKOUT_URL → defensive cancel path → finish().
        val controller = launch()
        val activity = controller.get()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun withCheckoutUrl_loadsTheUrlInWebView() {
        val controller = launch { putExtra(ZeroSettleWebViewActivity.EXTRA_CHECKOUT_URL, "https://checkout.example/abc") }
        val activity = controller.get()
        val webView = activity.webViewField()!!
        assertThat(shadowOf(webView).lastLoadedUrl).isEqualTo("https://checkout.example/abc")
        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun shouldOverrideUrlLoading_callbackUrl_finishesActivity() {
        val controller = launch { putExtra(ZeroSettleWebViewActivity.EXTRA_CHECKOUT_URL, "https://checkout.example/abc") }
        val activity = controller.get()
        val webView = activity.webViewField()!!
        val client = webView.client()

        val callback = "zerosettle://checkout/return?status=success&transaction_id=txn_1"
        val request = stubRequest(callback)
        val handled = client.shouldOverrideUrlLoading(webView, request)

        assertThat(handled).isTrue()
        assertThat(activity.isFinishing).isTrue()
        // Sanity: the URL the activity intercepted is in fact a callback URL.
        assertThat(WebCheckoutFlow.isCallbackUrl(callback)).isTrue()
    }

    @Test
    fun shouldOverrideUrlLoading_nonCallbackUrl_returnsFalseAndStays() {
        val controller = launch { putExtra(ZeroSettleWebViewActivity.EXTRA_CHECKOUT_URL, "https://checkout.example/abc") }
        val activity = controller.get()
        val webView = activity.webViewField()!!
        val client = webView.client()

        val request = stubRequest("https://example.com/some/intra/flow")
        val handled = client.shouldOverrideUrlLoading(webView, request)

        assertThat(handled).isFalse()
        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun backPress_noWebViewHistory_finishesActivity() {
        val controller = launch { putExtra(ZeroSettleWebViewActivity.EXTRA_CHECKOUT_URL, "https://checkout.example/abc") }
        val activity = controller.start().resume().get()

        // Fresh WebView → canGoBack() is false → cancel path → finish().
        activity.onBackPressedDispatcher.onBackPressed()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    @Suppress("DEPRECATION") // ShadowWebView.setCanGoBack is deprecated in Robolectric 4.13, still functional
    fun backPress_withWebViewHistory_navigatesBackInsteadOfFinishing() {
        val controller = launch { putExtra(ZeroSettleWebViewActivity.EXTRA_CHECKOUT_URL, "https://checkout.example/page1") }
        val activity = controller.start().resume().get()
        val webView = activity.webViewField()!!
        // Robolectric's ShadowWebView doesn't auto-track history on loadUrl —
        // setCanGoBack flips the canGoBack() result directly so the activity's
        // back-press branch under test takes the goBack path.
        val shadow = shadowOf(webView)
        shadow.setCanGoBack(true)
        assertThat(webView.canGoBack()).isTrue()
        val priorGoBacks = shadow.goBackInvocations

        activity.onBackPressedDispatcher.onBackPressed()

        // Back press should route to webView.goBack(), NOT finish the activity.
        assertThat(shadow.goBackInvocations).isEqualTo(priorGoBacks + 1)
        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun callbackUrl_isCancelled_isAcceptedByCompleteWebCheckout() {
        // Synthesised cancel URL the activity uses internally must be a
        // valid callback URL recognised by the SDK's parser. This pins the
        // contract — if WebCheckoutFlow ever changes its cancel parsing
        // (e.g., requires `status=canceled` only), this test catches it.
        val cancelUrl = Uri.Builder()
            .scheme(WebCheckoutFlow.CALLBACK_SCHEME)
            .authority(WebCheckoutFlow.CALLBACK_HOST)
            .path(WebCheckoutFlow.CALLBACK_PATH)
            .appendQueryParameter("status", "cancelled")
            .build()
            .toString()
        val parsed = WebCheckoutFlow.parseCallback(cancelUrl)
        assertThat(parsed).isEqualTo(WebCheckoutFlow.CallbackResult.Cancelled)
    }

    @Test
    fun newIntent_carriesCheckoutUrlExtra() {
        val ctx: android.app.Activity =
            Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        val intent = ZeroSettleWebViewActivity.newIntent(ctx, "https://checkout.example/xyz")
        assertThat(intent.component?.className).isEqualTo(ZeroSettleWebViewActivity::class.java.name)
        assertThat(intent.getStringExtra(ZeroSettleWebViewActivity.EXTRA_CHECKOUT_URL))
            .isEqualTo("https://checkout.example/xyz")
    }

    // ─── bottom-sheet structural tests ─────────────────────────────────

    @Test
    fun sheetLayout_webViewIsHostedInsideInnerSheetContainer() {
        val controller = launch { putExtra(ZeroSettleWebViewActivity.EXTRA_CHECKOUT_URL, "https://checkout.example/abc") }
        val activity = controller.get()
        val webView = activity.webViewField()!!
        val sheet = activity.sheetContainerField()!!
        val scrim = activity.scrimRootField()!!

        // WebView is somewhere inside the sheet container (sheet → body → webView)…
        assertThat(viewContains(sheet, webView)).isTrue()
        // …but the sheet itself is a child of the scrim, not the WebView's direct parent.
        assertThat(scrim.indexOfChild(sheet)).isAtLeast(0)
    }

    @Test
    fun scrimClick_callsHandleCancelAndFinishes() {
        // Outer scrim click = tap-outside-the-sheet → cancel pathway → finish().
        val controller = launch { putExtra(ZeroSettleWebViewActivity.EXTRA_CHECKOUT_URL, "https://checkout.example/abc") }
        val activity = controller.get()
        val scrim = activity.scrimRootField()!!

        scrim.performClick()

        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun sheetClick_doesNotDismiss() {
        // Tapping inside the sheet must NOT dismiss — the sheet consumes
        // the click. Without isClickable on the sheet container the tap
        // would propagate up to the scrim's listener.
        val controller = launch { putExtra(ZeroSettleWebViewActivity.EXTRA_CHECKOUT_URL, "https://checkout.example/abc") }
        val activity = controller.get()
        val sheet = activity.sheetContainerField()!!

        // Verify clickable + perform a click; activity must not finish.
        assertThat(sheet.isClickable).isTrue()
        sheet.performClick()

        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun closeButton_stillTriggersCancel() {
        // Locate the close ImageButton inside the sheet body and tap it.
        val controller = launch { putExtra(ZeroSettleWebViewActivity.EXTRA_CHECKOUT_URL, "https://checkout.example/abc") }
        val activity = controller.get()
        val sheet = activity.sheetContainerField()!!

        val closeButton = findCloseButton(sheet)
        assertThat(closeButton).isNotNull()
        closeButton!!.performClick()

        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun usesSheetThemeFromManifest() {
        // Sanity check the manifest <-> code wiring: the activity's theme
        // entry from PackageManager must resolve to Theme.ZeroSettleSheet.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = ctx.packageManager.getActivityInfo(
            android.content.ComponentName(ctx, ZeroSettleWebViewActivity::class.java),
            0,
        )
        // Theme resource id should equal R.style.Theme_ZeroSettleSheet.
        assertThat(info.themeResource).isEqualTo(com.zerosettle.sdk.R.style.Theme_ZeroSettleSheet)
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private fun stubRequest(url: String): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    private fun viewContains(parent: ViewGroup, target: View): Boolean {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child === target) return true
            if (child is ViewGroup && viewContains(child, target)) return true
        }
        return false
    }

    private fun findCloseButton(parent: ViewGroup): android.widget.ImageButton? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is android.widget.ImageButton) return child
            if (child is ViewGroup) {
                val found = findCloseButton(child)
                if (found != null) return found
            }
        }
        return null
    }
}
