package com.zerosettle.sdk.core

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class HttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HttpClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = HttpClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            publishableKey = "zs_pk_test_abc",
            sdkVersion = "1.0.0",
        )
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun get_sendsPublishableKeyHeader() = runTest {
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        client.get("/v1/iap/products/")
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("X-ZeroSettle-Key")).isEqualTo("zs_pk_test_abc")
        assertThat(recorded.getHeader("X-ZS-SDK-Version")).isEqualTo("1.0.0")
        assertThat(recorded.getHeader("X-ZS-SDK-Platform")).isEqualTo("android")
    }

    @Test fun get_4xx_returnsFailureBackendError() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val res = client.get("/v1/iap/missing/")
        assertThat(res.isFailure).isTrue()
        val err = res.exceptionOrNull() as com.zerosettle.sdk.models.ZeroSettleError.BackendError
        assertThat(err.statusCode).isEqualTo(404)
        assertThat(err.body).isEqualTo("nope")
    }

    @Test fun get_2xx_returnsSuccessBody() = runTest {
        server.enqueue(MockResponse().setBody("hello"))
        val res = client.get("/v1/iap/x/")
        assertThat(res.getOrNull()).isEqualTo("hello")
    }

    @Test fun post_sendsJsonBody() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        client.post("/v1/iap/play-store-transactions/", body = "{\"a\":1}")
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.body.readUtf8()).isEqualTo("{\"a\":1}")
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json")
    }

    @Test fun get_retriesOn5xx_thenSucceeds() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("ok"))
        val res = client.get("/v1/iap/x/")
        assertThat(res.getOrNull()).isEqualTo("ok")
    }
}
