package com.zerosettle.sdk.core

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class BackendCheckoutTest {
    private lateinit var server: MockWebServer
    private lateinit var backend: Backend

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        backend = Backend(
            baseUrl = server.url("/").toString().trimEnd('/'),
            publishableKey = "zs_pk_test_abc",
            sdkVersion = "1.0.0",
        )
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun createWebCheckout_returnsUrlFromResponse() = runTest {
        server.enqueue(MockResponse().setBody("""{"checkout_url":"https://checkout.zerosettle.com/c/abc"}"""))
        val res = backend.createWebCheckout(
            userId = "u1", productId = "pro_monthly",
            playPurchaseToken = null, customerName = null, customerEmail = null,
        )
        assertThat(res.getOrNull()?.checkoutUrl).isEqualTo("https://checkout.zerosettle.com/c/abc")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/iap/checkout-configs/")
        assertThat(recorded.body.readUtf8()).contains("\"product_id\":\"pro_monthly\"")
    }

    @Test fun createWebCheckout_withPlayToken_includesIt() = runTest {
        server.enqueue(MockResponse().setBody("""{"checkout_url":"https://c/x"}"""))
        backend.createWebCheckout(
            userId = "u1", productId = "pro_monthly",
            playPurchaseToken = "ptok_123", customerName = "Al", customerEmail = "a@x",
        )
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"play_purchase_token\":\"ptok_123\"")
        assertThat(body).contains("\"customer_name\":\"Al\"")
    }

    @Test fun createWebCheckout_503ServiceUnavailable_mapsToPlayApiUnreachable() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setBody(
                """{"error":"service_temporarily_unavailable","error_code":"PLAY_API_UNREACHABLE","retry_after_seconds":60}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(503).setBody(
                """{"error":"service_temporarily_unavailable","error_code":"PLAY_API_UNREACHABLE","retry_after_seconds":60}""",
            ),
        )
        val res = backend.createWebCheckout(
            userId = "u1", productId = "pro_monthly",
            playPurchaseToken = "ptok_123", customerName = null, customerEmail = null,
        )
        assertThat(res.isFailure).isTrue()
        assertThat(res.exceptionOrNull()).isInstanceOf(ZeroSettleError.PlayApiUnreachable::class.java)
    }
}
