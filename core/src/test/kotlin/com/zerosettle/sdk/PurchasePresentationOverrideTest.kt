package com.zerosettle.sdk

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.checkout.CheckoutPresentation
import com.zerosettle.sdk.checkout.ZeroSettleWebViewActivity
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Covers the two SDK gaps closed in this commit:
 *  - per-call `presentation` parameter on `ZeroSettle.purchase` (iOS Kit parity)
 *  - `releasePendingCheckout()` + Custom Tab lifecycle auto-cancel
 *
 * The presentation precedence cases assert which surface is launched by
 * spying on the shadow Activity's `nextStartedActivity` — Custom Tab,
 * WebView, or external browser (`Intent.ACTION_VIEW`).
 */
@RunWith(RobolectricTestRunner::class)
class PurchasePresentationOverrideTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        ZeroSettle.configure(
            ApplicationProvider.getApplicationContext(),
            ZeroSettleConfig(
                publishableKey = "zs_pk_test_abc",
                baseUrlOverride = server.url("/").toString().trimEnd('/'),
                syncPlayPurchases = false,
            ),
        )
    }

    @After fun tearDown() { server.shutdown(); ZeroSettle.resetForTesting() }

    /** Mockwebserver dispatcher with route prefixes — see ZeroSettleWebPurchaseTest for context. */
    private fun routeBy(routes: Map<String, () -> MockResponse>): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty().substringBefore('?')
            val match = routes.entries.firstOrNull { (prefix, _) -> path.startsWith(prefix) }
            return match?.value?.invoke()
                ?: MockResponse().setResponseCode(404).setBody("unmocked path: $path")
        }
    }

    private fun dispatcherWithServerPresentation(serverPresentation: String?): Dispatcher = routeBy(
        mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
            "/v1/iap/checkout-configs/" to {
                val presentationJson = serverPresentation?.let { ""","checkout_presentation":"$it"""" } ?: ""
                MockResponse().setBody(
                    """{"checkout_url":"https://checkout.example/abc"$presentationJson}""",
                )
            },
            "/v1/iap/transactions/" to { MockResponse().setBody(
                """{"id":"txn_1","product_id":"pro_monthly","status":"completed","source":"web_checkout","purchased_at":"2026-05-11T00:00:00Z"}""",
            ) },
        ),
    )

    /**
     * Per-call override `INLINE` beats a server response specifying `CUSTOM_TAB` —
     * the SDK launches the embedded WebView activity instead of a Custom Tab.
     */
    @Test fun override_INLINE_beats_server_CUSTOM_TAB() = runBlocking<Unit> {
        server.dispatcher = dispatcherWithServerPresentation("custom_tab")
        ZeroSettle.identify(Identity.User(id = "u1"))

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Default)
        val purchaseJob = scope.async {
            ZeroSettle.purchase(activity, productId = "pro_monthly", presentation = CheckoutPresentation.INLINE)
        }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }

        val started = shadowOf(activity).nextStartedActivity
        assertThat(started).isNotNull()
        assertThat(started.component?.className).isEqualTo(ZeroSettleWebViewActivity::class.java.name)

        // Cleanly resolve so the slot doesn't leak across tests.
        ZeroSettle.completeWebCheckout("zerosettle://checkout/return?status=success&transaction_id=txn_1")
        withTimeout(5000) { purchaseJob.await() }
    }

    /**
     * Per-call override `BROWSER` routes through `launchExternalBrowser` — the
     * launched intent has `ACTION_VIEW`, NOT the WebView activity component.
     */
    @Test fun override_BROWSER_routes_through_external_browser() = runBlocking<Unit> {
        server.dispatcher = dispatcherWithServerPresentation("custom_tab")
        ZeroSettle.identify(Identity.User(id = "u1"))

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Default)
        val purchaseJob = scope.async {
            ZeroSettle.purchase(activity, productId = "pro_monthly", presentation = CheckoutPresentation.BROWSER)
        }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }

        val started = shadowOf(activity).nextStartedActivity
        assertThat(started).isNotNull()
        assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
        // External browser path doesn't target our WebView activity.
        assertThat(started.component?.className).isNotEqualTo(ZeroSettleWebViewActivity::class.java.name)

        // Cleanly cancel — there's no easy way to resolve a "browser" path in unit test.
        purchaseJob.cancel()
        withTimeout(5000) { while (ZeroSettle.pendingCheckout.value) delay(10) }
    }

    /**
     * `presentation = null` + server says `CUSTOM_TAB` → launches a Custom Tab
     * (`Intent.ACTION_VIEW` against the checkout URL, no host component set —
     * the AndroidX Browser library issues that as a normal `ACTION_VIEW`).
     */
    @Test fun null_override_with_server_CUSTOM_TAB_launches_custom_tab() = runBlocking<Unit> {
        server.dispatcher = dispatcherWithServerPresentation("custom_tab")
        ZeroSettle.identify(Identity.User(id = "u1"))

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Default)
        val purchaseJob = scope.async {
            ZeroSettle.purchase(activity, productId = "pro_monthly", presentation = null)
        }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }

        val started = shadowOf(activity).nextStartedActivity
        assertThat(started).isNotNull()
        // CustomTabsIntent ultimately issues an ACTION_VIEW with the URL —
        // we just need to confirm the WebView activity wasn't used.
        assertThat(started.component?.className).isNotEqualTo(ZeroSettleWebViewActivity::class.java.name)

        ZeroSettle.completeWebCheckout("zerosettle://checkout/return?status=success&transaction_id=txn_1")
        withTimeout(5000) { purchaseJob.await() }
    }

    /**
     * `presentation = null` + no server presentation hint → falls through to the
     * safe Custom Tab default.
     */
    @Test fun null_override_with_no_server_presentation_falls_through_to_custom_tab() = runBlocking<Unit> {
        server.dispatcher = dispatcherWithServerPresentation(serverPresentation = null)
        ZeroSettle.identify(Identity.User(id = "u1"))

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Default)
        val purchaseJob = scope.async {
            ZeroSettle.purchase(activity, productId = "pro_monthly")
        }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }

        val started = shadowOf(activity).nextStartedActivity
        assertThat(started).isNotNull()
        assertThat(started.component?.className).isNotEqualTo(ZeroSettleWebViewActivity::class.java.name)

        ZeroSettle.completeWebCheckout("zerosettle://checkout/return?status=success&transaction_id=txn_1")
        withTimeout(5000) { purchaseJob.await() }
    }

    // ─── releasePendingCheckout() ─────────────────────────────────────────────

    /**
     * When called against an armed slot, `releasePendingCheckout()` flips
     * `pendingCheckout` back to false and completes the awaiting deferred
     * exceptionally with `PurchaseCancelled` — propagating through
     * `purchase()` as `Result.failure(PurchaseCancelled)`.
     */
    @Test fun releasePendingCheckout_cancels_armed_in_flight_purchase() = runBlocking<Unit> {
        server.dispatcher = dispatcherWithServerPresentation("custom_tab")
        ZeroSettle.identify(Identity.User(id = "u1"))

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Default)
        val purchaseJob = scope.async { ZeroSettle.purchase(activity, productId = "pro_monthly") }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }

        ZeroSettle.releasePendingCheckout()

        val result = withTimeout(5000) { purchaseJob.await() }
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(ZeroSettleError.PurchaseCancelled::class.java)
        assertThat(ZeroSettle.pendingCheckout.value).isFalse()
    }

    /** Idempotent: calling against an empty slot is a no-op (no crash). */
    @Test fun releasePendingCheckout_with_no_slot_armed_is_noop() {
        // No identify / purchase — slot is null. Should not throw.
        ZeroSettle.releasePendingCheckout()
        assertThat(ZeroSettle.pendingCheckout.value).isFalse()
    }

    /**
     * Two concurrent `releasePendingCheckout()` calls — only one completes the
     * deferred; the second runs through the no-op branch (synchronized snapshot
     * sees a null slot).
     */
    @Test fun releasePendingCheckout_double_call_is_safe() = runBlocking<Unit> {
        server.dispatcher = dispatcherWithServerPresentation("custom_tab")
        ZeroSettle.identify(Identity.User(id = "u1"))

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Default)
        val purchaseJob = scope.async { ZeroSettle.purchase(activity, productId = "pro_monthly") }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }

        ZeroSettle.releasePendingCheckout()
        // Second call must not throw and must not break the resolved deferred.
        ZeroSettle.releasePendingCheckout()

        val result = withTimeout(5000) { purchaseJob.await() }
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(ZeroSettleError.PurchaseCancelled::class.java)
    }

    // ─── installCustomTabDismissalWatcher ─────────────────────────────────────

    /**
     * Drives the lifecycle watcher directly (the spec marks the function
     * `@VisibleForTesting internal`). The watcher must:
     *
     *  - NOT fire on the initial RESUMED replay from `addObserver`
     *  - Fire on RESUME following an explicit PAUSE
     *  - Wait the 500ms grace window before checking the slot
     *  - Auto-unregister so a second PAUSE/RESUME pair doesn't fire again
     */
    @Test fun lifecycle_watcher_fires_on_ON_RESUME_after_ON_PAUSE() = runBlocking<Unit> {
        ZeroSettle.identify(Identity.User(id = "u1"))

        val deferred = ZeroSettle.armPendingCheckoutForTesting()
        val owner = TestLifecycleOwner(initialState = Lifecycle.State.RESUMED)
        // Install BEFORE the pause — mirrors what purchase() does (install
        // immediately after launching the Custom Tab, while the host is still
        // resumed from the Activity's perspective just before Chrome takes over).
        ZeroSettle.installCustomTabDismissalWatcher(owner, deferred)

        // Verify the replay-on-attach (ON_RESUME synthesised when an observer
        // is attached to an already-RESUMED owner) does NOT trip the watcher.
        // Wait past the grace window — if `wasPaused` weren't gating the fire,
        // we'd see the deferred completed by now.
        delay(800)
        assertThat(deferred.isCompleted).isFalse()

        // Now simulate the Custom Tab launch: PAUSE the host, then RESUME.
        owner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Wait past the grace window + scheduling slack.
        withTimeout(3000) {
            while (!deferred.isCompleted) delay(20)
        }
        assertThat(deferred.isCompleted).isTrue()
        assertThat(ZeroSettle.pendingCheckout.value).isFalse()
    }

    /**
     * If a deep-link arrives during the grace window, the watcher's poll finds
     * the slot already cleared (purchase()'s finally ran) and skips the
     * cancellation. The deferred completes with success, NOT PurchaseCancelled.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun lifecycle_watcher_noops_when_deep_link_arrives_during_grace_window() = runBlocking<Unit> {
        ZeroSettle.identify(Identity.User(id = "u1"))

        val deferred = ZeroSettle.armPendingCheckoutForTesting()
        val owner = TestLifecycleOwner(initialState = Lifecycle.State.RESUMED)
        ZeroSettle.installCustomTabDismissalWatcher(owner, deferred)

        owner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Resolve normally inside the grace window.
        deferred.complete("txn_xyz")

        // Wait past the grace window.
        delay(800)
        // The deferred should remain successfully completed (not cancelled).
        assertThat(deferred.isCompleted).isTrue()
        assertThat(deferred.isCancelled).isFalse()
        assertThat(deferred.getCompleted()).isEqualTo("txn_xyz")
    }

}
