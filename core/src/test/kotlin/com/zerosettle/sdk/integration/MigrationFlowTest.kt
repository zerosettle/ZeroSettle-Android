package com.zerosettle.sdk.integration

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.Identity
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.ZeroSettleConfig
import com.zerosettle.sdk.models.ZeroSettleError
import com.zerosettle.sdk.offers.OfferManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration: Switch & Save (Play → web) — evaluate → accept → web checkout → migration
 * conversion, against a mocked backend. Exercises Phases 5-6 end to end. A red here is a
 * real Phase-6 bug.
 *
 * Note on request counts: [com.zerosettle.sdk.core.HttpClient] retries a 5xx once, so the
 * "service temporarily unavailable" path enqueues the 503 twice.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationFlowTest {
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

    private val playEntitlements =
        """{"entitlements":[{"id":"e1","product_id":"pro_monthly","source":"play_store","is_active":true,"status":"active","will_renew":true,"purchased_at":"2026-05-01T00:00:00Z","play_purchase_token":"ptok_live"}]}"""

    private suspend fun identifyWithPlaySub() {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(MockResponse().setBody(playEntitlements))
        ZeroSettle.identify(Identity.User(id = "u1"))
        server.takeRequest(); server.takeRequest() // drain bootstrap
    }

    private fun eligibleOfferBody() =
        """{"is_eligible":true,"source":"play_store","offer":{
          "flow_type":"migration","upgrade_type":null,"source_storefront":"play_store",
          "product_id":"pro_monthly_web","eligible_product_ids":["pro_monthly"],"savings_percent":20,
          "display":{"offer_title":"Save 20%","offer_message":"","offer_cta":"Switch","accepted_title":"","accepted_message":"","accepted_cta":"","completed_title":"","completed_message":""},
          "free_trial_days":7,"min_subscription_days":14,"max_subscription_days":null,"rollout_percent":100,"checkout_presentation":"custom_tab","disclosure_text":"You'll be billed via Google Pay."}}"""

    @Test fun playToWebMigration_evaluate_accept_complete() = runTest {
        identifyWithPlaySub()

        server.enqueue(MockResponse().setBody(eligibleOfferBody()))
        val mgr = ZeroSettle.offerManager()
        mgr.evaluate()
        assertThat(mgr.state.first()).isEqualTo(OfferManager.OfferState.PRESENTED)
        assertThat(mgr.offerData.first()?.disclosureText).contains("Google Pay")
        server.takeRequest() // drain the user-offer request

        // acceptOffer() → POST /v1/iap/checkout-configs/ with play_purchase_token → checkout URL.
        server.enqueue(MockResponse().setBody("""{"checkout_url":"https://checkout.zerosettle.com/c/mig"}"""))
        val accept = mgr.acceptOffer()
        assertThat(accept.isSuccess).isTrue()
        assertThat(mgr.state.first()).isEqualTo(OfferManager.OfferState.ACCEPTED)
        val ccReq = server.takeRequest()
        assertThat(ccReq.path).isEqualTo("/v1/iap/checkout-configs/")
        assertThat(ccReq.body.readUtf8()).contains("\"play_purchase_token\":\"ptok_live\"")

        // onWebCheckoutSucceeded() → POST /v1/iap/migration-converted/ with source play_store.
        server.enqueue(MockResponse().setResponseCode(204))
        mgr.onWebCheckoutSucceeded()
        val convReq = server.takeRequest()
        assertThat(convReq.path).isEqualTo("/v1/iap/migration-converted/")
        assertThat(convReq.body.readUtf8()).contains("\"source\":\"play_store\"")
        // Still ACCEPTED — the SDK does NOT cancel the Play sub; the backend does, and the
        // entitlement still says will_renew=true so the auto-renew-off gate hasn't tripped.
        assertThat(mgr.state.first()).isEqualTo(OfferManager.OfferState.ACCEPTED)
    }

    @Test fun playToWebMigration_serviceTemporarilyUnavailable_surfacesRetryableError() = runTest {
        identifyWithPlaySub()

        server.enqueue(MockResponse().setBody(eligibleOfferBody()))
        val mgr = ZeroSettle.offerManager()
        mgr.evaluate()
        server.takeRequest() // drain the user-offer request

        // 503 PLAY_API_UNREACHABLE — HttpClient retries 5xx once, so enqueue twice.
        val unavailable = MockResponse().setResponseCode(503)
            .setBody("""{"error":"service_temporarily_unavailable","error_code":"PLAY_API_UNREACHABLE","retry_after_seconds":60}""")
        server.enqueue(unavailable)
        server.enqueue(MockResponse().setResponseCode(503)
            .setBody("""{"error":"service_temporarily_unavailable","error_code":"PLAY_API_UNREACHABLE","retry_after_seconds":60}"""))
        val res = mgr.acceptOffer()
        assertThat(res.isFailure).isTrue()
        assertThat(mgr.checkoutError.first()).isInstanceOf(ZeroSettleError.PlayApiUnreachable::class.java)
        assertThat(mgr.state.first()).isEqualTo(OfferManager.OfferState.PRESENTED) // can retry
    }
}
