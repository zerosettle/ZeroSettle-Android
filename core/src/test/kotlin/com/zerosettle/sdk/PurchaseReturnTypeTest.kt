package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.CheckoutTransaction
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

/**
 * Verifies the new high-level contract for `ZeroSettle.purchase`:
 * the function now returns `Result<CheckoutTransaction>` end-to-end —
 * the checkout URL is an internal detail and must not leak out.
 *
 * Mirrors iOS `ZeroSettle.shared.purchase(...)` which awaits the
 * deep-link return and resolves with the hydrated transaction.
 */
@RunWith(RobolectricTestRunner::class)
class PurchaseReturnTypeTest {

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
     * Compile-time-checked: assigning the call result to a `Result<CheckoutTransaction>`
     * binding is what proves the contract. If `purchase()` reverts to returning
     * `Result<String>` this test stops compiling. We invoke without `identify()` so
     * the call short-circuits with `UserNotIdentified` — no network involved.
     */
    @Test fun `purchase return type binds to Result of CheckoutTransaction`() = runTest {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val typed: Result<CheckoutTransaction> = ZeroSettle.purchase(activity, productId = "pro_monthly")
        // Force the compiler to emit the bind (some compilers fold unused bindings).
        assertThat(typed.isFailure).isTrue()
    }

    /**
     * End-to-end: after a successful checkout + callback, `purchase()` resolves with
     * a hydrated CheckoutTransaction (re-fetched from `GET /v1/iap/transactions/<id>/`).
     */
    @Test fun `purchase suspends until completeWebCheckout then returns CheckoutTransaction`() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    path.startsWith("/v1/iap/products/") ->
                        MockResponse().setBody("""{"products":[]}""")
                    path.startsWith("/v1/iap/entitlements/") ->
                        MockResponse().setBody("""{"entitlements":[]}""")
                    path.startsWith("/v1/iap/checkout-configs/") ->
                        MockResponse().setBody("""{"checkout_url":"https://checkout.zerosettle.com/c/abc"}""")
                    path.startsWith("/v1/iap/transactions/") ->
                        MockResponse().setBody(
                            """{"id":"txn_1","product_id":"pro_monthly","status":"completed","source":"web_checkout","purchased_at":"2026-05-11T00:00:00Z"}""",
                        )
                    else -> MockResponse().setResponseCode(404).setBody("unmocked: $path")
                }
            }
        }
        ZeroSettle.identify(Identity.User(id = "u1"))

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val purchaseScope = CoroutineScope(Dispatchers.Default)
        val deferredPurchase = purchaseScope.async {
            ZeroSettle.purchase(activity, productId = "pro_monthly")
        }
        // Wait until the bridge is armed (pendingCheckout flips to true once the
        // Custom Tab has been launched and the awaiter is suspended on the deferred).
        withTimeout(5000) {
            while (!ZeroSettle.pendingCheckout.value) delay(10)
        }

        ZeroSettle.completeWebCheckout("zerosettle://checkout/return?status=success&transaction_id=txn_1")

        val result = withTimeout(5000) { deferredPurchase.await() }
        assertThat(result.isSuccess).isTrue()
        val txn = result.getOrNull()!!
        assertThat(txn.id).isEqualTo("txn_1")
        assertThat(txn.productId).isEqualTo("pro_monthly")
        assertThat(txn.status).isEqualTo(CheckoutTransaction.Status.COMPLETED)
    }
}
