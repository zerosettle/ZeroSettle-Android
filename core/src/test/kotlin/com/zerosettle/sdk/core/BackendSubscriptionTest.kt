package com.zerosettle.sdk.core

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class BackendSubscriptionTest {
    private lateinit var server: MockWebServer
    private lateinit var backend: Backend

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        backend = Backend(server.url("/").toString().trimEnd('/'), "zs_pk_test_abc", "1.0.0")
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun cancelSubscription_posts() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        backend.cancelSubscription(userId = "u1", productId = "pro_monthly", immediate = true)
        val r = server.takeRequest()
        assertThat(r.method).isEqualTo("POST")
        assertThat(r.path).isEqualTo("/v1/iap/subscriptions/cancel/")
        assertThat(r.body.readUtf8()).contains("\"immediate\":true")
    }

    @Test fun pauseSubscription_returnsResumeDate() = runTest {
        server.enqueue(MockResponse().setBody("""{"resumes_at":"2026-06-01T00:00:00Z"}"""))
        val res = backend.pauseSubscription(userId = "u1", productId = "pro_monthly", pauseDurationDays = 30)
        assertThat(res.getOrNull()?.resumesAt).isEqualTo("2026-06-01T00:00:00Z")
        assertThat(server.takeRequest().path).isEqualTo("/v1/iap/subscriptions/pause/")
    }

    @Test fun resumeSubscription_posts() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        backend.resumeSubscription(userId = "u1", productId = "pro_monthly")
        assertThat(server.takeRequest().path).isEqualTo("/v1/iap/subscriptions/resume/")
    }

    @Test fun fetchCancelFlowConfig_decodes() = runTest {
        server.enqueue(MockResponse().setBody("""{"questions":[],"pause_options_days":[7,30]}"""))
        val res = backend.fetchCancelFlowConfig(userId = "u1")
        assertThat(res.getOrNull()?.pauseOptionsDays).containsExactly(7, 30).inOrder()
        assertThat(server.takeRequest().path).contains("/v1/iap/cancel-flow/")
    }

    @Test fun acceptSaveOffer_decodes() = runTest {
        server.enqueue(MockResponse().setBody("""{"product_id":"pro_monthly","savings_percent":50}"""))
        val res = backend.acceptSaveOffer(userId = "u1", productId = "pro_monthly")
        assertThat(res.getOrNull()?.savingsPercent).isEqualTo(50)
        assertThat(server.takeRequest().path).isEqualTo("/v1/iap/cancel-flow/accept-offer/")
    }

    @Test fun fetchUpgradeOfferConfig_decodes() = runTest {
        // The real `GET /v1/iap/upgrade-offer/` (chunk-5) wire shape.
        server.enqueue(
            MockResponse().setBody(
                """{"available":true,"savings_percent":33,"upgrade_type":"web_to_web",
                   "current_product":{"reference_id":"pro_monthly","name":"Monthly","price_cents":999,"currency":"USD","billing_label":"$9.99/mo"},
                   "target_product":{"reference_id":"pro_yearly","name":"Yearly","price_cents":7999,"currency":"USD","billing_label":"$79.99/yr","monthly_equivalent_cents":667},
                   "proration":{"amount_cents":-250,"currency":"USD","next_billing_date":"2026-06-20T00:00:00Z"},
                   "display":{"title":"Save 33%","body":"Switch to annual.","cta_text":"Upgrade","dismiss_text":"Not now"},
                   "variant_id":7}""",
            ),
        )
        val res = backend.fetchUpgradeOfferConfig(userId = "u1", productId = "pro_monthly")
        val cfg = res.getOrNull()
        assertThat(cfg?.available).isTrue()
        assertThat(cfg?.targetProduct?.referenceId).isEqualTo("pro_yearly")
        assertThat(cfg?.targetProduct?.monthlyEquivalentCents).isEqualTo(667)
        assertThat(cfg?.proration?.amountCents).isEqualTo(-250)
        assertThat(cfg?.display?.title).isEqualTo("Save 33%")
        assertThat(server.takeRequest().path).contains("/v1/iap/upgrade-offer/")
    }

    @Test fun fetchUpgradeOfferConfig_notAvailable_decodes() = runTest {
        // A not-available response has no product/proration/display blocks.
        server.enqueue(
            MockResponse().setBody("""{"available":false,"reason":"already_highest_tier"}"""),
        )
        val res = backend.fetchUpgradeOfferConfig(userId = "u1", productId = null)
        val cfg = res.getOrNull()
        assertThat(cfg?.available).isFalse()
        assertThat(cfg?.reason).isEqualTo("already_highest_tier")
        assertThat(cfg?.targetProduct).isNull()
    }

    @Test fun executeUpgradeOffer_posts() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":true,"upgrade_type":"storekit_to_web","target_product_id":"pro_yearly"}"""))
        val res = backend.executeUpgradeOffer(userId = "u1", currentProductId = "pro_monthly", targetProductId = "pro_yearly")
        assertThat(res.getOrNull()?.newProductId).isEqualTo("pro_yearly")
        assertThat(server.takeRequest().path).isEqualTo("/v1/iap/upgrade-offer/execute/")
    }

    @Test fun trackMigrationConversion_withPlaySource() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        backend.trackMigrationConversion(userId = "u1", source = "play_store")
        assertThat(server.takeRequest().body.readUtf8()).contains("\"source\":\"play_store\"")
    }
}
