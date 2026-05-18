package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZeroSettlePublicApiTest {

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

    private suspend fun identifyU1() {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))
        // Bootstrap also fetches `/v1/iap/play-billing-config/` for UCB.
        // 404 = endpoint not shipped / not opted in; bootstrap is best-effort.
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        ZeroSettle.identify(Identity.User(id = "u1"))
    }

    @Test fun fetchProducts_withoutIdentify_returnsUserNotIdentified() = runTest {
        val res = ZeroSettle.fetchProducts()
        assertThat(res.isFailure).isTrue()
        assertThat(res.exceptionOrNull()).isInstanceOf(ZeroSettleError.UserNotIdentified::class.java)
    }

    @Test fun restoreEntitlements_withoutIdentify_returnsUserNotIdentified() = runTest {
        val res = ZeroSettle.restoreEntitlements()
        assertThat(res.exceptionOrNull()).isInstanceOf(ZeroSettleError.UserNotIdentified::class.java)
    }

    @Test fun fetchTransactionHistory_withoutIdentify_returnsUserNotIdentified() = runTest {
        val res = ZeroSettle.fetchTransactionHistory()
        assertThat(res.exceptionOrNull()).isInstanceOf(ZeroSettleError.UserNotIdentified::class.java)
    }

    @Test fun fetchProducts_afterIdentify_succeedsAndUpdatesStateFlow() = runTest {
        identifyU1()
        server.enqueue(
            MockResponse().setBody(
                """{"products":[{"id":"pro_monthly","display_name":"Pro","product_description":"d","type":"auto_renewable_subscription"}]}""",
            ),
        )
        val res = ZeroSettle.fetchProducts()
        assertThat(res.getOrNull()?.products).hasSize(1)
        assertThat(ZeroSettle.products.value).hasSize(1)
    }

    @Test fun restoreEntitlements_returnsListAndUpdatesStateFlow() = runTest {
        identifyU1()
        val body = """
        {"entitlements":[{"id":"e1","product_id":"pro","source":"web_checkout","is_active":true,"status":"active","purchased_at":"2026-05-11T00:00:00Z"}]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body))
        val res = ZeroSettle.restoreEntitlements()
        assertThat(res.getOrNull()).hasSize(1)
        assertThat(ZeroSettle.entitlements.value).hasSize(1)
    }

    @Test fun fetchTransactionHistory_afterIdentify_returnsEmptyList() = runTest {
        identifyU1()
        server.enqueue(MockResponse().setBody("""{"transactions":[]}"""))
        val res = ZeroSettle.fetchTransactionHistory()
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrNull()).isEmpty()
    }

    @Test fun fetchTransactionHistory_decodesWireShape() = runTest {
        identifyU1()
        val body = """
        {
          "transactions": [
            {
              "id": "txn_1",
              "product_id": "pro_monthly",
              "status": "completed",
              "source": "web_checkout",
              "purchased_at": "2026-05-11T00:00:00Z",
              "expires_at": "2026-06-11T00:00:00Z",
              "product_name": "Pro Monthly",
              "amount_cents": 599,
              "currency": "usd",
              "storekit_status": null
            },
            {
              "id": "txn_2",
              "product_id": "lifetime",
              "status": "refunded",
              "source": "store_kit",
              "purchased_at": "2026-04-01T00:00:00Z",
              "expires_at": null,
              "product_name": "Lifetime",
              "amount_cents": 4999,
              "currency": "usd",
              "storekit_status": 5
            }
          ]
        }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body))
        val res = ZeroSettle.fetchTransactionHistory()
        assertThat(res.isSuccess).isTrue()
        val list = res.getOrNull()!!
        assertThat(list).hasSize(2)
        assertThat(list[0].id).isEqualTo("txn_1")
        assertThat(list[0].productId).isEqualTo("pro_monthly")
        assertThat(list[0].status).isEqualTo(com.zerosettle.sdk.models.CheckoutTransaction.Status.COMPLETED)
        assertThat(list[0].source).isEqualTo(com.zerosettle.sdk.models.EntitlementSource.WEB_CHECKOUT)
        assertThat(list[0].amountCents).isEqualTo(599)
        assertThat(list[0].currency).isEqualTo("usd")
        assertThat(list[0].storekitStatus).isNull()
        assertThat(list[1].status).isEqualTo(com.zerosettle.sdk.models.CheckoutTransaction.Status.REFUNDED)
        assertThat(list[1].source).isEqualTo(com.zerosettle.sdk.models.EntitlementSource.STORE_KIT)
        assertThat(list[1].storekitStatus).isEqualTo(5)
    }

    @Test fun fetchTransactionHistory_malformedBody_returnsBackendError() = runTest {
        identifyU1()
        server.enqueue(MockResponse().setBody("""not json"""))
        val res = ZeroSettle.fetchTransactionHistory()
        assertThat(res.isFailure).isTrue()
        assertThat(res.exceptionOrNull()).isInstanceOf(ZeroSettleError.BackendError::class.java)
    }
}
