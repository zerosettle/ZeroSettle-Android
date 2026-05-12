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

    @Test fun fetchTransactionHistory_afterIdentify_returnsRawJson() = runTest {
        identifyU1()
        server.enqueue(MockResponse().setBody("""{"transactions":[]}"""))
        val res = ZeroSettle.fetchTransactionHistory()
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrNull()).contains("transactions")
    }
}
