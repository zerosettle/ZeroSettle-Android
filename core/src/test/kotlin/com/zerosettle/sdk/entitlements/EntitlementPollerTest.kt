package com.zerosettle.sdk.entitlements

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.core.Backend
import com.zerosettle.sdk.models.PendingAction
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class EntitlementPollerTest {
    private lateinit var server: MockWebServer
    private lateinit var backend: Backend

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        backend = Backend(server.url("/").toString().trimEnd('/'), "zs_pk_test_abc", "1.0.0")
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun pollOnce_publishesEntitlementsAndPendingActions() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"entitlements":[{"id":"e1","product_id":"pro","source":"play_store","is_active":true,"status":"active","purchased_at":"2026-05-11T00:00:00Z"}],
                   "pending_actions":[{"type":"manual_play_cancel","transaction_id":"t","original_play_purchase_token":"p","expires_at":"x","deep_link":"y","user_message":"m"}]}""",
            ),
        )
        var publishedActions: List<PendingAction>? = null
        var publishedEntCount = -1
        val poller = EntitlementPoller(
            backend = backend, userIdProvider = { "u1" },
            onEntitlements = { publishedEntCount = it.size },
            onPendingActions = { publishedActions = it },
            onUnknownActionType = { },
        )
        poller.pollOnce()
        assertThat(publishedEntCount).isEqualTo(1)
        assertThat(publishedActions).hasSize(1)
        assertThat(publishedActions!!.first()).isInstanceOf(PendingAction.ManualPlayCancel::class.java)
        assertThat(server.takeRequest().path).isEqualTo("/v1/iap/entitlements/?user_id=u1")
    }

    @Test fun pollOnce_withoutUserId_isNoOp() = runTest {
        val poller = EntitlementPoller(
            backend = backend, userIdProvider = { null },
            onEntitlements = { error("should not be called") }, onPendingActions = { error("should not be called") },
            onUnknownActionType = { },
        )
        poller.pollOnce()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun pollOnce_backendFailure_doesNotPublish() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        var called = false
        val poller = EntitlementPoller(
            backend = backend, userIdProvider = { "u1" },
            onEntitlements = { called = true }, onPendingActions = { called = true }, onUnknownActionType = { },
        )
        poller.pollOnce()
        assertThat(called).isFalse()
    }

    @Test fun pollOnce_unknownActionType_forwardedToCallback() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"entitlements":[],"pending_actions":[{"type":"future_v9","transaction_id":"t","user_message":"?"}]}""",
            ),
        )
        val logged = mutableListOf<String>()
        var actions: List<PendingAction>? = null
        val poller = EntitlementPoller(
            backend = backend, userIdProvider = { "u1" },
            onEntitlements = { }, onPendingActions = { actions = it }, onUnknownActionType = { logged += it },
        )
        poller.pollOnce()
        assertThat(actions).isEmpty()
        assertThat(logged).hasSize(1)
        assertThat(logged.first()).contains("future_v9")
    }

    @Test fun foregroundIntervalMillis_isFiveMinutes() {
        assertThat(EntitlementPoller.FOREGROUND_POLL_INTERVAL_MILLIS).isEqualTo(5L * 60 * 1000)
    }
}
