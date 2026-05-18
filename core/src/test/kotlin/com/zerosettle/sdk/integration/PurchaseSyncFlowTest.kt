package com.zerosettle.sdk.integration

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.billing.PlaySyncQueue
import com.zerosettle.sdk.billing.PurchaseDescriptor
import com.zerosettle.sdk.billing.PurchaseSyncProcessor
import com.zerosettle.sdk.core.Backend
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration: a freshly observed Play purchase travels origination → backend sync →
 * acknowledgement → queue drain, against a mocked backend. Exercises Phase-5 code end
 * to end (happy + degraded paths). A failure here is a real Phase-5 regression.
 */
@RunWith(RobolectricTestRunner::class)
class PurchaseSyncFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var queue: PlaySyncQueue
    private lateinit var backend: Backend
    private val acked = mutableListOf<String>()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        queue = PlaySyncQueue(ApplicationProvider.getApplicationContext()).also { runTest { it.clear() } }
        backend = Backend(server.url("/").toString().trimEnd('/'), "zs_pk_test_abc", "1.0.0")
        acked.clear()
    }

    @After fun tearDown() { server.shutdown() }

    private fun processor(now: () -> Long = { 0L }) = PurchaseSyncProcessor(
        backend = backend, queue = queue,
        finalize = { _, token -> acked += token; Result.success(Unit) }, emitEvent = { },
        strictAck = false, nowMillis = now,
    )

    private fun descriptor() = PurchaseDescriptor(
        purchaseToken = "tok_flow", productId = "pro_monthly", packageName = "com.app",
        userId = "u1", orderId = "GPA.flow", purchaseState = 1, isAcknowledged = false,
        signature = "sig", originalJson = "{}", willAutoRenew = true, customerName = null, customerEmail = null,
    )

    @Test fun happyPath_syncSucceeds_acksAndDrainsQueue() = runTest {
        server.enqueue(MockResponse().setBody("""{"owned":true,"transaction_id":"txn_flow","entitlement_id":"ent_flow"}"""))
        val res = processor().process(descriptor())
        assertThat(res.isSuccess).isTrue()
        assertThat(acked).containsExactly("tok_flow")
        assertThat(queue.pending()).isEmpty()
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/iap/play-store-transactions/")
        val body = req.body.readUtf8()
        assertThat(body).contains("\"purchase_token\":\"tok_flow\"")
        assertThat(body).contains("\"will_auto_renew\":true")
    }

    @Test fun degradedPath_backendDownThenUp_eventuallySyncsAndAcks() = runTest {
        // First attempt: backend down ×2 → HttpClient exhausts its 1 retry → enqueue.
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        val first = processor().process(descriptor())
        assertThat(first.isFailure).isTrue()
        assertThat(acked).isEmpty()
        assertThat(queue.pending()).hasSize(1)

        // Backend back up — retryQueued drains it. backoff for attempt 1 is 5s; nowMillis=10s clears the gate.
        server.enqueue(MockResponse().setBody("""{"owned":true,"transaction_id":"txn_flow"}"""))
        processor(now = { 10_000L }).retryQueued()
        assertThat(acked).contains("tok_flow")
        assertThat(queue.pending()).isEmpty()
    }
}
