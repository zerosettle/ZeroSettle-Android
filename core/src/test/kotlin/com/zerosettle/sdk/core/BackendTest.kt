package com.zerosettle.sdk.core

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class BackendTest {
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

    @Test fun fetchProducts_decodesCatalog() = runTest {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        val res = backend.fetchProducts(userId = "u1")
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrNull()?.products).isEmpty()
        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("/v1/iap/products/")
        assertThat(recorded.path).contains("user_id=u1")
    }

    @Test fun fetchEntitlements_decodesEntitlementsAndPendingActions() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"entitlements":[{"id":"ent_1","product_id":"p","source":"web_checkout","is_active":true,"status":"active"}],
                   "pending_actions":[{"type":"manual_play_cancel","product_id":"p"}],
                   "pending_claims":[]}""",
            ),
        )
        val res = backend.fetchEntitlements(userId = "u1")
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrNull()?.entitlements).hasSize(1)
        assertThat(res.getOrNull()?.pendingActions).hasSize(1)
        assertThat(server.takeRequest().path).contains("/v1/iap/entitlements/")
    }

    @Test fun fetchUserOffer_decodesResponse() = runTest {
        server.enqueue(MockResponse().setBody("""{"is_eligible":false}"""))
        val res = backend.fetchUserOffer(userId = "u1")
        assertThat(res.getOrNull()?.isEligible).isFalse()
        assertThat(server.takeRequest().path).contains("/v1/iap/user-offer/")
    }

    @Test fun createCheckoutConfig_postsBodyVerbatim() = runTest {
        server.enqueue(MockResponse().setBody("""{"checkout_url":"https://x"}"""))
        val res = backend.createCheckoutConfig(body = """{"user_id":"u1","product_id":"p","play_purchase_token":"tok"}""")
        assertThat(res.isSuccess).isTrue()
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/iap/checkout-configs/")
        assertThat(recorded.body.readUtf8()).contains("\"play_purchase_token\":\"tok\"")
    }

    @Test fun dismissMigrationAction_postsUrlAndBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        backend.dismissMigrationAction(transactionId = "zs_txn_1", userId = "u1", actionType = "info_banner_dismissed")
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1/iap/migration-actions/zs_txn_1/dismiss/")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"user_id\":\"u1\"")
        assertThat(body).contains("\"action_type\":\"info_banner_dismissed\"")
    }

    @Test fun syncPlayPurchase_postsAndDecodesResponse() = runTest {
        server.enqueue(MockResponse().setBody("""{"owned":true,"transaction_id":"txn_1"}"""))
        val res = backend.syncPlayPurchase(
            userId = "u1",
            purchaseToken = "tok",
            productId = "pro_monthly",
            packageName = "com.app",
            orderId = "GPA",
            purchaseState = 1,
            isAcknowledged = false,
            signature = "sig",
            originalJson = "{}",
            willAutoRenew = true,
            customerName = null,
            customerEmail = null,
        )
        assertThat(res.getOrNull()?.owned).isTrue()
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/iap/play-store-transactions/")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"purchase_token\":\"tok\"")
        assertThat(body).contains("\"package_name\":\"com.app\"")
    }
}
