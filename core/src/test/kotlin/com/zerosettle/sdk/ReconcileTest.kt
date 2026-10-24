package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
class ReconcileTest {
    private lateinit var server: MockWebServer
    private var reconcileResponse: () -> MockResponse = { MockResponse().setResponseCode(500) }
    private var reconcileCalls = 0
    private val logger = RecordingLogger()

    @Before fun setUp() {
        server = MockWebServer().also {
            it.dispatcher = routeBy(
                bootstrapRoutes() + ("/v1/iap/identities/reconcile/" to { _ -> reconcileCalls++; reconcileResponse() }),
            )
            it.start()
        }
        ZeroSettle.configure(
            ApplicationProvider.getApplicationContext(),
            ZeroSettleConfig(
                publishableKey = "zs_pk_test_abc",
                baseUrlOverride = server.url("/").toString().trimEnd('/'),
                syncPlayPurchases = false,
                logger = logger,
            ),
        )
    }

    @After fun tearDown() { server.shutdown(); ZeroSettle.resetForTesting() }

    private fun ok(from: String, into: String) = MockResponse().setBody(
        """{"reconciliation_id":7,"from":"$from","into":"$into","moved":{"transactions":2,"entitlements":1}}""",
    )

    @Test fun success_mapsResult_andSwitchesCurrentUserWithoutWarning() = runTest {
        ZeroSettle.identify(Identity.User(id = "Guest-1"))
        reconcileResponse = { ok("guest-1", "acct-9") }
        val res = ZeroSettle.reconcile("tok").getOrThrow()
        assertThat(res.reconciliationId).isEqualTo(7)
        assertThat(res.fromUserId).isEqualTo("guest-1")
        assertThat(res.intoUserId).isEqualTo("acct-9")
        assertThat(res.movedTransactions).isEqualTo(2)
        assertThat(res.movedEntitlements).isEqualTo(1)
        assertThat(ZeroSettle.activeUserIdForTesting()).isEqualTo("acct-9")
        assertThat(ZeroSettle.userSwitchWarningCount).isEqualTo(0)
    }

    @Test fun success_whenCurrentIsAnotherUser_leavesItAlone() = runTest {
        ZeroSettle.identify(Identity.User(id = "someone"))
        reconcileResponse = { ok("guest-1", "acct-9") }
        assertThat(ZeroSettle.reconcile("tok").isSuccess).isTrue()
        assertThat(ZeroSettle.activeUserIdForTesting()).isEqualTo("someone")
    }

    @Test fun success_adoptsFromAnonymousUuid() = runTest {
        ZeroSettle.identify(Identity.Anonymous)
        val anon = ZeroSettle.activeUserIdForTesting()!!
        reconcileResponse = { ok(anon, "acct-9") }
        ZeroSettle.reconcile("tok").getOrThrow()
        assertThat(ZeroSettle.activeUserIdForTesting()).isEqualTo("acct-9")
    }

    @Test fun conflict_isReconcileRefused_andNotRetried() = runTest {
        reconcileResponse = { MockResponse().setResponseCode(409).setBody("""{"error":{"code":"reconcile_refused"}}""") }
        assertThat(ZeroSettle.reconcile("tok").exceptionOrNull()).isEqualTo(ZeroSettleError.ReconcileRefused)
        assertThat(reconcileCalls).isEqualTo(1)
    }

    @Test fun invalidToken_is401InvalidLinkToken() = runTest {
        reconcileResponse = { MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_link_token"}}""") }
        assertThat(ZeroSettle.reconcile("tok").exceptionOrNull()).isEqualTo(ZeroSettleError.InvalidLinkToken)
    }

    @Test fun other401_isBackendError() = runTest {
        reconcileResponse = { MockResponse().setResponseCode(401).setBody("""{"error":{"code":"nope"}}""") }
        assertThat(ZeroSettle.reconcile("tok").exceptionOrNull()).isInstanceOf(ZeroSettleError.BackendError::class.java)
    }

    @Test fun rateLimited_carriesRetryAfter() = runTest {
        reconcileResponse = { MockResponse().setResponseCode(429).setHeader("Retry-After", "30") }
        assertThat(ZeroSettle.reconcile("tok").exceptionOrNull()).isEqualTo(ZeroSettleError.RateLimited(30L))
    }

    @Test fun rateLimited_withoutHeader_isNull() = runTest {
        reconcileResponse = { MockResponse().setResponseCode(429) }
        assertThat(ZeroSettle.reconcile("tok").exceptionOrNull()).isEqualTo(ZeroSettleError.RateLimited(null))
    }

    @Test fun serverError_isBackendError_andNotRetried() = runTest {
        reconcileResponse = { MockResponse().setResponseCode(503).setBody("down") }
        assertThat(ZeroSettle.reconcile("tok").exceptionOrNull()).isInstanceOf(ZeroSettleError.BackendError::class.java)
        assertThat(reconcileCalls).isEqualTo(1)
    }
}
