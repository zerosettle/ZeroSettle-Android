package com.zerosettle.sdk

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
class ZeroSettleWebPurchaseTest {

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

    /**
     * Route MockWebServer responses by URL path. The new `purchase()` flow has
     * a race where the deferred resolves first (so `fetchTransaction` may fire
     * before `restoreEntitlements` finishes), and the FIFO `enqueue` model
     * gives non-deterministic results. A path-keyed dispatcher resolves it.
     */
    private fun routeBy(routes: Map<String, () -> MockResponse>): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty().substringBefore('?')
            val match = routes.entries.firstOrNull { (prefix, _) -> path.startsWith(prefix) }
            return match?.value?.invoke()
                ?: MockResponse().setResponseCode(404).setBody("unmocked path: $path")
        }
    }

    @Test fun purchase_withoutIdentify_returnsUserNotIdentified() = runTest {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val res = ZeroSettle.purchase(activity, productId = "pro_monthly")
        assertThat(res.exceptionOrNull()).isInstanceOf(ZeroSettleError.UserNotIdentified::class.java)
    }

    /**
     * End-to-end: purchase() drives the Custom Tab launch, sets pendingCheckout, then
     * suspends until completeWebCheckout() arrives and refetches the hydrated record.
     */
    @Test fun purchase_afterIdentify_launchesCheckoutAndCompletesWithTransaction() = runBlocking<Unit> {
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
            "/v1/iap/checkout-configs/" to { MockResponse().setBody("""{"checkout_url":"https://checkout.zerosettle.com/c/abc"}""") },
            "/v1/iap/transactions/" to { MockResponse().setBody(
                """{"id":"txn_1","product_id":"pro_monthly","status":"completed","source":"web_checkout","purchased_at":"2026-05-11T00:00:00Z"}""",
            ) },
        ))
        ZeroSettle.identify(Identity.User(id = "u1"))

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Default)
        val deferredPurchase = scope.async { ZeroSettle.purchase(activity, productId = "pro_monthly") }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }

        ZeroSettle.completeWebCheckout("zerosettle://checkout/return?status=success&transaction_id=txn_1")

        val res = withTimeout(5000) { deferredPurchase.await() }
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrNull()?.id).isEqualTo("txn_1")
        assertThat(res.getOrNull()?.productId).isEqualTo("pro_monthly")
        assertThat(ZeroSettle.pendingCheckout.value).isFalse()
    }

    @Test fun completeWebCheckout_clearsPendingAndRefreshesEntitlements() = runBlocking<Unit> {
        var entitlementsResponseBody = """{"entitlements":[]}"""
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody(entitlementsResponseBody) },
            "/v1/iap/checkout-configs/" to { MockResponse().setBody("""{"checkout_url":"https://c/x"}""") },
            "/v1/iap/transactions/" to { MockResponse().setBody(
                """{"id":"txn_1","product_id":"pro_monthly","status":"completed","source":"web_checkout","purchased_at":"2026-05-11T00:00:00Z"}""",
            ) },
        ))
        ZeroSettle.identify(Identity.User(id = "u1"))
        // Flip the entitlements response AFTER bootstrap so completeWebCheckout's
        // restoreEntitlements call sees the active subscription.
        entitlementsResponseBody = """{"entitlements":[{"id":"e1","product_id":"pro_monthly","source":"web_checkout","is_active":true,"status":"active","purchased_at":"2026-05-11T00:00:00Z"}]}"""

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Default)
        val deferredPurchase = scope.async { ZeroSettle.purchase(activity, productId = "pro_monthly") }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }

        ZeroSettle.completeWebCheckout("zerosettle://checkout/return?status=success&transaction_id=txn_1")
        withTimeout(5000) { deferredPurchase.await() }
        assertThat(ZeroSettle.pendingCheckout.value).isFalse()
        // Eventually restoreEntitlements (kicked off by completeWebCheckout) settles.
        withTimeout(5000) { while (ZeroSettle.entitlements.value.size != 1) delay(10) }
        assertThat(ZeroSettle.entitlements.value).hasSize(1)
    }

    /**
     * Regression: if `restoreEntitlements()` fails on the deep-link return (network
     * blip), the awaiting `purchase()` call must still resolve — the user-visible
     * checkout already succeeded and the deferred must not gate on a transient
     * entitlement-refresh failure.
     */
    @Test fun purchase_resolves_even_when_restoreEntitlements_fails() = runBlocking<Unit> {
        // Bootstrap returns empty entitlements; the second entitlements call (from
        // completeWebCheckout) will get a 500 — we flip the dispatcher to fail
        // only the second call by toggling a counter.
        var entitlementsHits = 0
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to {
                entitlementsHits++
                if (entitlementsHits == 1) MockResponse().setBody("""{"entitlements":[]}""")
                else MockResponse().setResponseCode(500).setBody("server boom")
            },
            "/v1/iap/checkout-configs/" to { MockResponse().setBody("""{"checkout_url":"https://c/x"}""") },
            "/v1/iap/transactions/" to { MockResponse().setBody(
                """{"id":"txn_1","product_id":"pro_monthly","status":"completed","source":"web_checkout","purchased_at":"2026-05-11T00:00:00Z"}""",
            ) },
        ))
        ZeroSettle.identify(Identity.User(id = "u1"))

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Default)
        val deferredPurchase = scope.async { ZeroSettle.purchase(activity, productId = "pro_monthly") }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }

        ZeroSettle.completeWebCheckout("zerosettle://checkout/return?status=success&transaction_id=txn_1")

        val res = withTimeout(5000) { deferredPurchase.await() }
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrNull()?.id).isEqualTo("txn_1")
    }

    /**
     * Concurrent `purchase()` calls — the second one fails fast with `CheckoutInFlight`
     * without hitting the network (the SDK serializes one checkout at a time).
     */
    @Test fun concurrent_purchase_calls_fail_with_CheckoutInFlight() = runBlocking<Unit> {
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
            "/v1/iap/checkout-configs/" to { MockResponse().setBody("""{"checkout_url":"https://c/a"}""") },
            "/v1/iap/transactions/" to { MockResponse().setBody(
                """{"id":"txn_1","product_id":"pro_monthly","status":"completed","source":"web_checkout","purchased_at":"2026-05-11T00:00:00Z"}""",
            ) },
        ))
        ZeroSettle.identify(Identity.User(id = "u1"))

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Default)
        val first = scope.async { ZeroSettle.purchase(activity, productId = "pro_monthly") }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }

        // Second call short-circuits BEFORE hitting the network — fast-fail check.
        val second = ZeroSettle.purchase(activity, productId = "pro_monthly")
        assertThat(second.isFailure).isTrue()
        assertThat(second.exceptionOrNull()).isInstanceOf(ZeroSettleError.CheckoutInFlight::class.java)

        // Cleanly resolve the first to avoid leaking state across tests.
        ZeroSettle.completeWebCheckout("zerosettle://checkout/return?status=success&transaction_id=txn_1")
        withTimeout(5000) { first.await() }
    }

    /**
     * An Activity subclass whose `startActivity` always throws — used to simulate
     * the I1 launch-failure path (e.g. host with no browser installed firing the
     * BROWSER presentation, or a CustomTabsIntent rejection).
     */
    private class ThrowingStartActivity : android.app.Activity() {
        override fun startActivity(intent: Intent?) {
            throw ActivityNotFoundException("no activity handles this intent (test stub)")
        }
        override fun startActivity(intent: Intent?, options: android.os.Bundle?) {
            throw ActivityNotFoundException("no activity handles this intent (test stub)")
        }
    }

    /**
     * Regression for I1: if launching the Custom Tab / external browser throws,
     * the bridge slot + `_pendingCheckout` flag must be reset. Previously the
     * slot leaked and subsequent `purchase()` calls were permanently locked out
     * with `CheckoutInFlight`.
     *
     * Uses `BROWSER` presentation → `activity.startActivity(Intent.ACTION_VIEW)`,
     * which our stub Activity makes throw.
     */
    @Test fun purchase_clears_slot_when_browser_launch_throws() = runBlocking<Unit> {
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
            // Backend asks for BROWSER presentation → routes through launchExternalBrowser
            // which calls activity.startActivity — our throwing stub triggers the leak path.
            "/v1/iap/checkout-configs/" to { MockResponse().setBody(
                """{"checkout_url":"https://c/x","checkout_presentation":"browser"}""",
            ) },
            "/v1/iap/transactions/" to { MockResponse().setBody(
                """{"id":"txn_x","product_id":"pro_monthly","status":"completed","source":"web_checkout","purchased_at":"2026-05-11T00:00:00Z"}""",
            ) },
        ))
        ZeroSettle.identify(Identity.User(id = "u1"))

        val throwingActivity = Robolectric.buildActivity(ThrowingStartActivity::class.java).setup().get()
        val raised: Throwable = try {
            ZeroSettle.purchase(throwingActivity, productId = "pro_monthly")
            error("expected purchase() to propagate ActivityNotFoundException")
        } catch (e: ActivityNotFoundException) {
            e
        }
        assertThat(raised).isInstanceOf(ActivityNotFoundException::class.java)
        // I2: the public flag must NOT be stuck at `true` after the launch throw.
        assertThat(ZeroSettle.pendingCheckout.value).isFalse()

        // I1: subsequent purchase() must NOT fail with CheckoutInFlight — the slot
        // was cleared by the finally block. We don't care about the eventual outcome
        // here (we'll cancel it), only that the guard passes.
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Default)
        val second = scope.async { ZeroSettle.purchase(activity, productId = "pro_monthly") }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }
        // If we got here, the second call passed the CheckoutInFlight guard and
        // armed a fresh deferred. Cleanly tear it down.
        second.cancel()
    }

    /**
     * Regression for I2: when the awaiter is cancelled mid-checkout (host scope
     * torn down before the deep-link return arrives), `_pendingCheckout.value`
     * must flip back to `false`. Previously it stayed at `true` — public
     * StateFlow consumers would see a stale "checkout in progress" forever.
     */
    @Test fun cancellation_mid_checkout_resets_pendingCheckout_flag() = runBlocking<Unit> {
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
            "/v1/iap/checkout-configs/" to { MockResponse().setBody("""{"checkout_url":"https://c/x"}""") },
        ))
        ZeroSettle.identify(Identity.User(id = "u1"))

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Default)
        val job: Job = scope.async { ZeroSettle.purchase(activity, productId = "pro_monthly") }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }
        assertThat(ZeroSettle.pendingCheckout.value).isTrue()

        // Cancel the awaiter mid-flight — the deep-link return never arrives.
        job.cancel()
        withTimeout(5000) { while (ZeroSettle.pendingCheckout.value) delay(10) }
        assertThat(ZeroSettle.pendingCheckout.value).isFalse()

        // And the bridge slot must be clear so a fresh purchase() is allowed.
        val activity2 = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val second = scope.async { ZeroSettle.purchase(activity2, productId = "pro_monthly") }
        withTimeout(5000) { while (!ZeroSettle.pendingCheckout.value) delay(10) }
        second.cancel()
    }
}
