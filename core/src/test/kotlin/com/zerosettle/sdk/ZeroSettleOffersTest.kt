package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.UserOffer
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZeroSettleOffersTest {

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

    private suspend fun identify() {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))
        ZeroSettle.identify(Identity.User(id = "u1"))
        // Drain the bootstrap requests so subsequent takeRequest() sees the call under test.
        server.takeRequest(); server.takeRequest()
    }

    @Test fun offerManager_beforeIdentify_throws() = runTest {
        try { ZeroSettle.offerManager(); error("expected throw") }
        catch (e: ZeroSettleError) { assertThat(e).isEqualTo(ZeroSettleError.UserNotIdentified) }
    }

    @Test fun offerManager_evaluate_resolvesPlayMigration() = runTest {
        identify()
        server.enqueue(
            MockResponse().setBody(
                """{"user_id":"u1","app_id":1,"is_sandbox":true,
                  "subscription":{"type":"active_storekit","product_id":"pro_monthly"},
                  "offer":{"action_type":"migrate_storekit_to_web","is_eligible":true,
                    "checkout_product_id":"pro_monthly_web","from_product_id":"pro_monthly","savings_percent":20,
                    "free_trial_days":7,"min_subscription_days":14,"rollout_percent":100,
                    "requires_apple_cancel":true,"checkout_presentation":"webview","source":"play_store"},
                  "server_time":"2026-05-12T00:00:00Z"}""",
            ),
        )
        val mgr = ZeroSettle.offerManager()
        mgr.evaluate()
        assertThat(mgr.state.first()).isEqualTo(com.zerosettle.sdk.offers.OfferManager.OfferState.PRESENTED)
        assertThat(mgr.offerData.first()?.source).isEqualTo(UserOffer.SourceStorefront.PLAY_STORE)
    }

    @Test fun fetchUserOffer_facade_returnsResponse() = runTest {
        identify()
        server.enqueue(
            MockResponse().setBody(
                """{"user_id":"u1","app_id":1,"is_sandbox":true,
                  "subscription":{"type":"none"},
                  "offer":{"action_type":"no_action","is_eligible":false,"checkout_product_id":""},
                  "server_time":"2026-05-12T00:00:00Z"}""",
            ),
        )
        val res = ZeroSettle.fetchUserOffer()
        assertThat(res.getOrNull()?.isEligible).isFalse()
    }

    @Test fun trackMigrationConversion_facade_postsPlayStoreSource() = runTest {
        identify()
        server.enqueue(MockResponse().setResponseCode(204))
        ZeroSettle.trackMigrationConversion(source = UserOffer.SourceStorefront.PLAY_STORE)
        assertThat(server.takeRequest().body.readUtf8()).contains("\"source\":\"play_store\"")
    }

    @Test fun cancelSubscription_facade_posts() = runTest {
        identify()
        server.enqueue(MockResponse().setResponseCode(204))
        // restoreEntitlements() detaches on Dispatchers.Default; the extra enqueue is its safety net.
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))
        val res = ZeroSettle.cancelSubscription(productId = "pro_monthly", immediate = false)
        assertThat(res.isSuccess).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/v1/iap/subscriptions/cancel/")
    }

    @Test fun transferPlayOwnership_facade_postsClaim() = runTest {
        identify()
        server.enqueue(MockResponse().setBody("""{}"""))               // claim-entitlement response
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))  // restoreEntitlements safety net
        val res = ZeroSettle.transferPlayOwnershipToCurrentUser(productId = "pro_monthly", originalTransactionId = "otid_1")
        assertThat(res.isSuccess).isTrue()
        val r = server.takeRequest()
        assertThat(r.path).isEqualTo("/v1/iap/claim-entitlement/")
        assertThat(r.body.readUtf8()).contains("\"original_transaction_id\":\"otid_1\"")
    }
}
