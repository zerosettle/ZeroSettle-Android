package com.zerosettle.sdk.billing

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.core.Backend
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class SubscriptionReconcilerTest {
    private lateinit var server: MockWebServer
    private lateinit var backend: Backend

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        backend = Backend(server.url("/").toString().trimEnd('/'), "zs_pk_test_abc", "1.0.0")
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun reconcile_postsArrayPayloadToPlayStoreTransactions() = runTest {
        server.enqueue(MockResponse().setBody("""{"processed":2,"events_emitted":1,"skipped":[]}"""))
        val res = SubscriptionReconciler(backend = backend).reconcile(
            userId = "u1",
            purchases = listOf(
                ReconcilePurchase("tok1", "pro_monthly", "com.app", "{}", "s1"),
                ReconcilePurchase("tok2", "pro_yearly", "com.app", "{}", "s2"),
            ),
        )
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrNull()?.processed).isEqualTo(2)
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/iap/play-store-transactions/")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"transactions\":[")
        assertThat(body).contains("\"purchase_token\":\"tok1\"")
        assertThat(body).contains("\"purchase_token\":\"tok2\"")
    }

    @Test fun reconcile_emptyPurchases_isNoOp() = runTest {
        val res = SubscriptionReconciler(backend = backend).reconcile(userId = "u1", purchases = emptyList())
        assertThat(res.getOrNull()?.processed).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
    }
}
