package com.zerosettle.sdk.billing

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.core.Backend
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PurchaseSyncProcessorTest {

    private lateinit var server: MockWebServer
    private lateinit var queue: PlaySyncQueue
    private lateinit var backend: Backend
    private val acked = mutableListOf<String>()

    private val resolvedTransactionIds = mutableListOf<String>()
    private val resolvedExceptions = mutableListOf<Throwable>()

    private fun makeProcessor(strictAck: Boolean = false) = PurchaseSyncProcessor(
        backend = backend,
        queue = queue,
        acknowledge = { token -> acked += token; Result.success(Unit) },
        emitEvent = { },
        onPurchaseSynced = { txnId -> resolvedTransactionIds += txnId },
        onPurchaseFailed = { err -> resolvedExceptions += err },
        strictAck = strictAck,
        nowMillis = { 0L },
    )

    private fun descriptor(token: String = "tok1") = PurchaseDescriptor(
        purchaseToken = token, productId = "pro_monthly", packageName = "com.app",
        userId = "u1", orderId = "GPA.1", purchaseState = 1, isAcknowledged = false,
        signature = "sig", originalJson = "{}", willAutoRenew = true,
        customerName = null, customerEmail = null,
    )

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        queue = PlaySyncQueue(ApplicationProvider.getApplicationContext()).also { runTest { it.clear() } }
        backend = Backend(server.url("/").toString().trimEnd('/'), "zs_pk_test_abc", "1.0.0")
        acked.clear()
        resolvedTransactionIds.clear()
        resolvedExceptions.clear()
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun process_ownedTrue_acknowledgesAndDoesNotEnqueue() = runTest {
        server.enqueue(MockResponse().setBody("""{"owned":true,"transaction_id":"txn_1"}"""))
        val res = makeProcessor().process(descriptor())
        assertThat(res.isSuccess).isTrue()
        assertThat(acked).containsExactly("tok1")
        assertThat(queue.pending()).isEmpty()
    }

    @Test fun process_backend5xx_enqueuesAndDoesNotAck() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val res = makeProcessor().process(descriptor())
        assertThat(res.isFailure).isTrue()
        assertThat(acked).isEmpty()
        assertThat(queue.pending()).hasSize(1)
        assertThat(queue.pending().first().attemptCount).isEqualTo(1)
    }

    @Test fun process_ownedFalseConflict_publishesPendingClaim_noAck() = runTest {
        server.enqueue(MockResponse().setBody("""{"owned":false,"conflict":true,"claim_available":true,"existing_owner_hint":"al***"}"""))
        var claimSeen: com.zerosettle.sdk.models.PendingClaim? = null
        val proc = PurchaseSyncProcessor(
            backend = backend, queue = queue,
            acknowledge = { Result.success(Unit) }, emitEvent = { },
            onConflictClaim = { claimSeen = it }, strictAck = false, nowMillis = { 0L },
        )
        proc.process(descriptor())
        assertThat(acked).isEmpty()
        assertThat(claimSeen?.existingOwnerHint).isEqualTo("al***")
        assertThat(queue.pending()).isEmpty()
    }

    // ─── Deferred-bridge wiring (Task A3) ────────────────────────────────────
    //
    // The listener-driven Play Billing flow needs a way to resume the suspending
    // `ZeroSettle.purchaseViaPlayBilling` call once the backend confirms the
    // purchase. Wiring lives on the processor via two callbacks:
    //
    //  - onPurchaseSynced(transactionId) — fired on `owned=true`, BEFORE the
    //    PurchaseSucceeded event emit (direct callers must resume before
    //    events-stream observers see the event).
    //  - onPurchaseFailed(error) — fired on conflict, not_owned, 5xx, pending.
    //
    // retryQueued() MUST NOT fire either callback: queued rows from a previous
    // session belong to a previous awaiter that is already gone. Resolving the
    // current-session deferred with an unrelated transactionId would be wrong.

    @Test fun process_ownedTrue_firesOnPurchaseSynced_withTransactionId() = runTest {
        server.enqueue(MockResponse().setBody("""{"owned":true,"transaction_id":"txn_1"}"""))
        makeProcessor().process(descriptor())
        assertThat(resolvedTransactionIds).containsExactly("txn_1")
        assertThat(resolvedExceptions).isEmpty()
    }

    @Test fun process_pendingState_firesOnPurchaseFailed_withPurchasePending() = runTest {
        // purchaseState == 2 short-circuits BEFORE the backend call. Without a
        // failure callback the suspending caller hangs forever waiting on the
        // deferred. This pins the resolve-on-pending contract.
        val pending = descriptor().copy(purchaseState = 2)
        makeProcessor().process(pending)
        assertThat(resolvedTransactionIds).isEmpty()
        assertThat(resolvedExceptions).hasSize(1)
        assertThat(resolvedExceptions.first()).isInstanceOf(
            com.zerosettle.sdk.models.ZeroSettleError.PurchasePending::class.java,
        )
    }

    @Test fun process_backend5xx_firesOnPurchaseFailed() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        makeProcessor().process(descriptor())
        assertThat(resolvedTransactionIds).isEmpty()
        assertThat(resolvedExceptions).hasSize(1)
    }

    @Test fun process_ownedFalseConflict_firesOnPurchaseFailed() = runTest {
        server.enqueue(MockResponse().setBody("""{"owned":false,"conflict":true,"claim_available":true,"existing_owner_hint":"al***"}"""))
        makeProcessor().process(descriptor())
        assertThat(resolvedTransactionIds).isEmpty()
        assertThat(resolvedExceptions).hasSize(1)
    }

    @Test fun process_ownedFalseNotOwned_firesOnPurchaseFailed() = runTest {
        server.enqueue(MockResponse().setBody("""{"owned":false}"""))
        makeProcessor().process(descriptor())
        assertThat(resolvedTransactionIds).isEmpty()
        assertThat(resolvedExceptions).hasSize(1)
    }

    @Test fun retryQueued_neverFiresAwaiterCallbacks() = runTest {
        // retryQueued() drains rows from prior sessions whose original awaiter is
        // gone. Resolving the *current* awaiter's deferred with one of those
        // transactionIds would deliver the wrong purchase. This pins that the
        // queue-drain success path never calls onPurchaseSynced/Failed.
        queue.enqueue(PendingPurchaseSync("tok_old", "pro_monthly", "com.app", "u1"))
        server.enqueue(MockResponse().setBody("""{"owned":true,"transaction_id":"txn_from_prior_session"}"""))
        makeProcessor().retryQueued()
        assertThat(resolvedTransactionIds).isEmpty()
        assertThat(resolvedExceptions).isEmpty()
    }

    @Test fun retryQueued_strictModeOff_oldEnoughAndStillFailing_defensiveAck() = runTest {
        val day = 24 * 60 * 60 * 1000L
        queue.enqueue(PendingPurchaseSync("tok1", "pro_monthly", "com.app", "u1", attemptCount = 4, lastAttemptAtMillis = 0L))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val proc = PurchaseSyncProcessor(
            backend = backend, queue = queue,
            acknowledge = { token -> acked += token; Result.success(Unit) }, emitEvent = { },
            strictAck = false, nowMillis = { day + 60 * 60 * 1000L },
        )
        proc.retryQueued()
        assertThat(acked).contains("tok1")
    }

    @Test fun retryQueued_strictModeOn_neverDefensiveAcks() = runTest {
        val day = 24 * 60 * 60 * 1000L
        queue.enqueue(PendingPurchaseSync("tok1", "pro_monthly", "com.app", "u1", attemptCount = 4, lastAttemptAtMillis = 0L))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val proc = PurchaseSyncProcessor(
            backend = backend, queue = queue,
            acknowledge = { token -> acked += token; Result.success(Unit) }, emitEvent = { },
            strictAck = true, nowMillis = { day + 60 * 60 * 1000L },
        )
        proc.retryQueued()
        assertThat(acked).isEmpty()
    }
}
