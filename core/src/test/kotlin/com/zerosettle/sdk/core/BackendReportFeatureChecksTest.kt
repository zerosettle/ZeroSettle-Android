package com.zerosettle.sdk.core

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class BackendReportFeatureChecksTest {
    @Test fun postsKeysWithHeader() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(204)); server.start()
        val backend = Backend(server.url("/").toString().trimEnd('/'), "pk_test_abc", "1.0")
        val result = backend.reportFeatureChecks(listOf("pro", "he\"llo"))
        val req = server.takeRequest()
        assertThat(result.isSuccess).isTrue()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/v1/iap/features/checked/")
        assertThat(req.getHeader("X-ZeroSettle-Key")).isEqualTo("pk_test_abc")
        assertThat(req.body.readUtf8()).isEqualTo("""{"keys":["pro","he\"llo"]}""")
        server.shutdown()
    }

    @Test fun badKeyIsAFailureWithItsStatus() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(401).setBody("{}")); server.start()
        val backend = Backend(server.url("/").toString().trimEnd('/'), "pk_bad", "1.0")
        val err = backend.reportFeatureChecks(listOf("a")).exceptionOrNull()
        assertThat((err as com.zerosettle.sdk.models.ZeroSettleError.BackendError).statusCode).isEqualTo(401)
        server.shutdown()
    }
}
