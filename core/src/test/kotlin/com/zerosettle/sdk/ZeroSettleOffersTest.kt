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
        // Bootstrap also fetches `/v1/iap/play-billing-config/` as part of the
        // UCB phase-2 integration. A 404 leaves the cached UcbConfig at the
        // disabled default — bootstrap proceeds normally and the SDK falls
        // back to standard Play Billing.
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        ZeroSettle.identify(Identity.User(id = "u1"))
        // Drain the bootstrap requests so subsequent takeRequest() sees the call under test.
        server.takeRequest(); server.takeRequest(); server.takeRequest()
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

    @Test fun offerManager_evaluate_switchAndSaveTestMode_surfacesPlayMigrationTip() = runTest {
        // Phase 3 implication: switchAndSaveTestMode = true alone makes the MIGRATE_PLAY_TO_WEB
        // ECL gate resolve available — the offer tip surfaces without setting eclAvailabilityOverride.
        identify()
        server.enqueue(
            MockResponse().setBody(
                """{"user_id":"u1","app_id":1,"is_sandbox":true,
                  "subscription":{"type":"active_storekit","product_id":"pro_monthly"},
                  "offer":{"action_type":"migrate_play_to_web","is_eligible":true,
                    "checkout_product_id":"pro_monthly_web","from_product_id":"pro_monthly","savings_percent":20,
                    "free_trial_days":7,"min_subscription_days":14,"rollout_percent":100,
                    "requires_apple_cancel":false,"checkout_presentation":"webview","source":"play_store"},
                  "server_time":"2026-05-12T00:00:00Z"}""",
            ),
        )
        ZeroSettle.eclAvailabilityOverride = null
        ZeroSettle.switchAndSaveTestMode = true
        try {
            val mgr = ZeroSettle.offerManager()
            mgr.evaluate()
            // PRESENTED proves isEclAvailable() resolved true via the test-mode short-circuit —
            // without it, the MIGRATE_PLAY_TO_WEB ECL gate would suppress the offer (INELIGIBLE).
            assertThat(mgr.state.first()).isEqualTo(com.zerosettle.sdk.offers.OfferManager.OfferState.PRESENTED)
        } finally {
            ZeroSettle.switchAndSaveTestMode = false
        }
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
        // Seed a pending claim carrying the Play purchase token, as a conflict
        // sync would. transferPlayOwnershipToCurrentUser sources the token from it.
        server.enqueue(
            MockResponse().setBody(
                """{"entitlements":[],"pending_claims":[
                   {"product_id":"pro_monthly","original_transaction_id":"otid_1",
                    "existing_owner_hint":"al***","purchase_token":"tok_play_1"}]}""",
            ),
        )
        ZeroSettle.restoreEntitlements()
        server.takeRequest() // drain the entitlements fetch
        assertThat(ZeroSettle.pendingClaims.first()).hasSize(1)

        server.enqueue(
            MockResponse().setBody(
                """{"status":"ok","claimed":true,"product_id":"pro_monthly",
                   "purchase_token":"tok_play_1","entitlement_states_transferred":1}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))  // restoreEntitlements safety net
        val res = ZeroSettle.transferPlayOwnershipToCurrentUser(productId = "pro_monthly")
        assertThat(res.isSuccess).isTrue()
        val r = server.takeRequest()
        assertThat(r.path).isEqualTo("/v1/iap/claim-play-entitlement/")
        val body = r.body.readUtf8()
        assertThat(body).contains("\"user_id\":\"u1\"")
        assertThat(body).contains("\"product_id\":\"pro_monthly\"")
        assertThat(body).contains("\"purchase_token\":\"tok_play_1\"")
        assertThat(body).contains("\"package_name\":")
        // The resolved claim is cleared on success.
        assertThat(ZeroSettle.pendingClaims.first()).isEmpty()
    }

    @Test fun transferPlayOwnership_noMatchingClaim_returnsNotFound() = runTest {
        identify()
        val res = ZeroSettle.transferPlayOwnershipToCurrentUser(productId = "pro_monthly")
        assertThat(res.isFailure).isTrue()
        assertThat(res.exceptionOrNull()).isInstanceOf(ZeroSettleError.NotFound::class.java)
    }

    @Test fun transferPlayOwnership_claimWithoutToken_returnsNotFound() = runTest {
        identify()
        // A claim from a backend that predates purchase_token → no token to claim with.
        server.enqueue(
            MockResponse().setBody(
                """{"entitlements":[],"pending_claims":[
                   {"product_id":"pro_monthly","original_transaction_id":"otid_1",
                    "existing_owner_hint":"al***"}]}""",
            ),
        )
        ZeroSettle.restoreEntitlements()
        server.takeRequest()
        val res = ZeroSettle.transferPlayOwnershipToCurrentUser(productId = "pro_monthly")
        assertThat(res.isFailure).isTrue()
        assertThat(res.exceptionOrNull()).isInstanceOf(ZeroSettleError.NotFound::class.java)
    }

    @Test fun transferPlayOwnership_backendRejects_returnsFailure() = runTest {
        identify()
        server.enqueue(
            MockResponse().setBody(
                """{"entitlements":[],"pending_claims":[
                   {"product_id":"pro_monthly","original_transaction_id":"otid_1",
                    "existing_owner_hint":"al***","purchase_token":"tok_play_1"}]}""",
            ),
        )
        ZeroSettle.restoreEntitlements()
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":"error"}"""))
        val res = ZeroSettle.transferPlayOwnershipToCurrentUser(productId = "pro_monthly")
        assertThat(res.isFailure).isTrue()
        // The claim is NOT cleared on failure — the user can retry.
        assertThat(ZeroSettle.pendingClaims.first()).hasSize(1)
    }
}
