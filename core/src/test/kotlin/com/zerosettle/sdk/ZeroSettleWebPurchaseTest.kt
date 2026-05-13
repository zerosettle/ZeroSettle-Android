package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
}
