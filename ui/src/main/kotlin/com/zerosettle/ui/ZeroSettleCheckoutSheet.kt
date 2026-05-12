package com.zerosettle.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zerosettle.sdk.checkout.WebCheckoutFlow
import com.zerosettle.sdk.offers.OfferManager
import kotlinx.coroutines.launch

/**
 * Terminal result surfaced by [ZeroSettleCheckoutSheet]. Named to avoid shadowing
 * `kotlin.Result`.
 */
public sealed class ZeroSettleCheckoutResult {
    public data class Succeeded(val transactionId: String?) : ZeroSettleCheckoutResult()
    public data object Cancelled : ZeroSettleCheckoutResult()
    public data class Failed(val reason: String) : ZeroSettleCheckoutResult()
}

/**
 * Compose surface hosting the Stripe web checkout in a `WebView`. Google Pay surfaces
 * automatically inside the Stripe Payment Element on an Android user-agent — there is
 * no bespoke GPay button (chunk-3 "Light" scope). Completion is detected by
 * intercepting the `zerosettle://checkout/return…` redirect via
 * [ZeroSettleCheckoutSheetNav.classifyNavigation]; the result is reported via [onResult].
 * Mirrors iOS's offer-flow `WKWebView` host.
 *
 * Devs who prefer a Chrome Custom Tab can call
 * `WebCheckoutFlow.launchCustomTab(activity, url)` from the headless core instead —
 * this sheet is for inline / bottom-sheet presentation. For the auto-bookkeeping path,
 * use [ZeroSettleCheckoutHost] which observes [OfferManager.pendingCheckoutUrl] and
 * threads the result back into the manager.
 *
 * **Naming-collision resolution:** the public `@Composable fun` keeps the
 * `ZeroSettleCheckoutSheet` name; the navigation classifier helper lives on the
 * sibling object [ZeroSettleCheckoutSheetNav] (Kotlin can't have a top-level `fun` and
 * an `object` of the same name).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
public fun ZeroSettleCheckoutSheet(
    checkoutUrl: String,
    onResult: (ZeroSettleCheckoutResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxWidth().height(640.dp).testTag("zerosettle_checkout_webview"),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        return when (val nav = ZeroSettleCheckoutSheetNav.classifyNavigation(url)) {
                            is ZeroSettleCheckoutSheetNav.NavResult.Succeeded -> { onResult(ZeroSettleCheckoutResult.Succeeded(nav.transactionId)); true }
                            ZeroSettleCheckoutSheetNav.NavResult.Cancelled -> { onResult(ZeroSettleCheckoutResult.Cancelled); true }
                            is ZeroSettleCheckoutSheetNav.NavResult.Failed -> { onResult(ZeroSettleCheckoutResult.Failed(nav.reason)); true }
                            ZeroSettleCheckoutSheetNav.NavResult.Continue -> false
                        }
                    }
                }
                loadUrl(checkoutUrl)
            }
        },
        onRelease = { it.destroy() },
    )
}

/**
 * Auto-bookkeeping checkout host. Observes [OfferManager.pendingCheckoutUrl]; while it's
 * non-null, presents [ZeroSettleCheckoutSheet] in a [ModalBottomSheet]. On a `Succeeded`
 * redirect → [OfferManager.onWebCheckoutSucceeded]; on `Cancelled` / bottom-sheet
 * dismiss → [OfferManager.cancelPendingCheckout]; on `Failed` → [OfferManager.cancelPendingCheckout]
 * plus [onFailed]. This is the wiring for the carried-forward "actually open the
 * checkout" gap — drop it next to [ZeroSettleOfferTip] and the offer flow is complete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ZeroSettleCheckoutHost(
    offerManager: OfferManager,
    modifier: Modifier = Modifier,
    onFailed: (reason: String) -> Unit = {},
) {
    val url by offerManager.pendingCheckoutUrl.collectAsState()
    val current = url ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = { offerManager.cancelPendingCheckout() },
        sheetState = sheetState,
        modifier = modifier,
    ) {
        ZeroSettleCheckoutSheet(
            checkoutUrl = current,
            onResult = { result ->
                when (result) {
                    is ZeroSettleCheckoutResult.Succeeded -> scope.launch { offerManager.onWebCheckoutSucceeded() }
                    ZeroSettleCheckoutResult.Cancelled -> offerManager.cancelPendingCheckout()
                    is ZeroSettleCheckoutResult.Failed -> { offerManager.cancelPendingCheckout(); onFailed(result.reason) }
                }
            },
        )
    }
}

/** Navigation classifier for [ZeroSettleCheckoutSheet] — a sibling of the composable, not a duplicate name. */
public object ZeroSettleCheckoutSheetNav {
    public sealed class NavResult {
        public data class Succeeded(val transactionId: String?) : NavResult()
        public data object Cancelled : NavResult()
        public data class Failed(val reason: String) : NavResult()
        public data object Continue : NavResult()
    }

    /** Map a WebView navigation URL to a [NavResult]. Non-callback URLs → [NavResult.Continue]. */
    public fun classifyNavigation(url: String): NavResult = when (val cb = WebCheckoutFlow.parseCallback(url)) {
        is WebCheckoutFlow.CallbackResult.Succeeded -> NavResult.Succeeded(cb.transactionId)
        WebCheckoutFlow.CallbackResult.Cancelled -> NavResult.Cancelled
        is WebCheckoutFlow.CallbackResult.Failed -> NavResult.Failed(cb.reason)
        null -> NavResult.Continue
    }
}
