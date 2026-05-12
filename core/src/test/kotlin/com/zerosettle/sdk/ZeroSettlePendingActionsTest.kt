package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.PendingAction
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZeroSettlePendingActionsTest {

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

    @Test fun bootstrap_populatesPendingActionsFromEntitlementsResponse() = runTest {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"entitlements":[],
                   "pending_actions":[{"type":"manual_play_cancel","transaction_id":"t","original_play_purchase_token":"p","expires_at":"x","deep_link":"y","user_message":"m"}]}""",
            ),
        )
        ZeroSettle.identify(Identity.User(id = "u1"))
        assertThat(ZeroSettle.pendingActions.value).hasSize(1)
        assertThat(ZeroSettle.pendingActions.value.first()).isInstanceOf(PendingAction.ManualPlayCancel::class.java)
    }

    @Test fun bootstrap_unknownActionType_dropped() = runTest {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"entitlements":[],"pending_actions":[{"type":"future_v9","transaction_id":"t","user_message":"m"}]}""",
            ),
        )
        ZeroSettle.identify(Identity.User(id = "u1"))
        assertThat(ZeroSettle.pendingActions.value).isEmpty()
    }

    @Test fun dismissPendingAction_postsAndDropsLocally() = runTest {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"entitlements":[],"pending_actions":[{"type":"migration_completed_info","transaction_id":"t","play_access_ends_at":"2026-06-01T00:00:00Z","new_subscription_price":{"amount_cents":499,"currency":"USD","billing_interval":"month"},"user_message":"m"}]}""",
            ),
        )
        ZeroSettle.identify(Identity.User(id = "u1"))
        assertThat(ZeroSettle.pendingActions.value).hasSize(1)
        val action = ZeroSettle.pendingActions.value.first()
        server.takeRequest(); server.takeRequest()  // drain the bootstrap products + entitlements requests

        server.enqueue(MockResponse().setResponseCode(204))
        val res = ZeroSettle.dismissPendingAction(action)
        assertThat(res.isSuccess).isTrue()
        assertThat(ZeroSettle.pendingActions.value).isEmpty()
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/iap/migration-actions/t/dismiss/")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"user_id\":\"u1\"")
        assertThat(body).contains("\"action_type\":\"info_banner_dismissed\"")
    }

    @Test fun dismissPendingAction_manualPlayCancel_usesCorrectActionType() = runTest {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"entitlements":[],"pending_actions":[{"type":"manual_play_cancel","transaction_id":"t2","original_play_purchase_token":"p","expires_at":"x","deep_link":"y","user_message":"m"}]}""",
            ),
        )
        ZeroSettle.identify(Identity.User(id = "u1"))
        val action = ZeroSettle.pendingActions.value.first()
        server.takeRequest(); server.takeRequest()  // drain bootstrap requests
        server.enqueue(MockResponse().setResponseCode(204))
        ZeroSettle.dismissPendingAction(action)
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"action_type\":\"manual_play_cancel_completed\"")
    }

    @Test fun dismissPendingAction_withoutIdentify_returnsUserNotIdentified() = runTest {
        val res = ZeroSettle.dismissPendingAction(
            PendingAction.MigrationCompletedInfo(
                transactionId = "t", userMessage = "m", playAccessEndsAtIso = null,
                newSubscriptionPriceCents = null, newSubscriptionCurrency = null, newSubscriptionInterval = null,
            ),
        )
        assertThat(res.exceptionOrNull()).isInstanceOf(ZeroSettleError.UserNotIdentified::class.java)
    }
}
