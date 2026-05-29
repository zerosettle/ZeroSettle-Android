package com.zerosettle.sdk.core

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class BackendReportOfferViewedTest {
    @Test fun postsToOfferViewedWithBody() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(200).setBody("{}")); server.start()
        val backend = Backend(server.url("/").toString().trimEnd('/'), "pk_test", "1.0")
        backend.reportOfferViewed("u1", "com.app.pro", "sess1", 7, "migration")
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/iap/offer-viewed/")
        val body = req.body.readUtf8()
        assertThat(body).contains(""""user_id":"u1"""")
        assertThat(body).contains(""""product_id":"com.app.pro"""")
        assertThat(body).contains(""""session_id":"sess1"""")
        assertThat(body).contains(""""variant_id":7""")
        assertThat(body).contains(""""flow_type":"migration"""")
        server.shutdown()
    }

    @Test fun omitsNullVariant() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(200).setBody("{}")); server.start()
        val backend = Backend(server.url("/").toString().trimEnd('/'), "pk_test", "1.0")
        backend.reportOfferViewed("u1", "p", "s", null, "migration")
        assertThat(server.takeRequest().body.readUtf8()).doesNotContain("variant_id")
        server.shutdown()
    }
}
