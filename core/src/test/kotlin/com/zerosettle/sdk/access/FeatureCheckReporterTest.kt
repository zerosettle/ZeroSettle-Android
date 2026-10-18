package com.zerosettle.sdk.access

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.ZeroSettleError
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureCheckReporterTest {
    private class FakeSender(val name: String = "k") : FeatureCheckSender {
        val batches = mutableListOf<List<String>>()
        var results = ArrayDeque<Result<Unit>>()
        var gate: CompletableDeferred<Unit>? = null
        val sentKeys get() = batches.flatten()
        override suspend fun send(keys: List<String>): Result<Unit> {
            batches.add(keys)
            gate?.await()
            return results.removeFirstOrNull() ?: Result.success(Unit)
        }
    }

    private fun TestScope.reporter(pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }) =
        FeatureCheckReporter(this, 2_000, 60_000, { testScheduler.currentTime }, pause)

    private fun TestScope.flush() { advanceTimeBy(2_001); runCurrent() }

    @Test fun sendsEachKeyOnce() = runTest {
        val r = reporter(); val s = FakeSender(); r.reset(s)
        r.note("a"); r.note("b"); flush()
        r.note("a"); r.note("c"); flush()
        assertThat(s.sentKeys).containsExactly("a", "b", "c").inOrder()
    }

    @Test fun batchesFiftyPerRequest() = runTest {
        val r = reporter(); val s = FakeSender(); r.reset(s)
        (1..120).forEach { r.note("k$it") }
        flush()
        assertThat(s.batches.map { it.size }).containsExactly(50, 50, 20).inOrder()
        assertThat(s.sentKeys.first()).isEqualTo("k1")
    }

    @Test fun skipsEmptyAndLongKeys() = runTest {
        val r = reporter(); val s = FakeSender(); r.reset(s)
        r.note(""); r.note("x".repeat(65)); r.note("x".repeat(64))
        // 33 emoji are 66 UTF-16 units but 33 code points, so allowed; 65 emoji are not
        r.note("😀".repeat(33)); r.note("😀".repeat(65))
        flush(); flush()
        assertThat(s.sentKeys).containsExactly("x".repeat(64), "😀".repeat(33))
    }

    @Test fun networkErrorRetriesAfterBackoff() = runTest {
        val r = reporter(); val s = FakeSender(); r.reset(s)
        s.results.add(Result.failure(ZeroSettleError.NetworkError(IOException("x"))))
        r.note("a"); flush()
        assertThat(s.batches).hasSize(1)
        advanceTimeBy(30_000); r.note("a"); flush()
        assertThat(s.batches).hasSize(1)
        advanceTimeBy(60_000); r.note("a"); flush()
        assertThat(s.batches).hasSize(2)
        r.note("a"); flush()
        assertThat(s.batches).hasSize(2)
    }

    @Test fun serverErrorKeepsKeysPending() = runTest {
        val r = reporter(); val s = FakeSender(); r.reset(s)
        s.results.add(Result.failure(ZeroSettleError.BackendError(500, "")))
        r.note("a"); flush()
        advanceTimeBy(61_000); r.note("b"); flush()
        assertThat(s.batches).containsExactly(listOf("a"), listOf("a", "b")).inOrder()
    }

    @Test fun badRequestIsNotRetried() = runTest {
        for (code in listOf(400, 401, 403)) {
            val r = reporter(); val s = FakeSender(); r.reset(s)
            s.results.add(Result.failure(ZeroSettleError.BackendError(code, "")))
            r.note("a"); flush()
            r.note("a"); flush()
            assertThat(s.batches).hasSize(1)
        }
    }

    @Test fun resetSendsAgain() = runTest {
        val r = reporter(); val s1 = FakeSender(); val s2 = FakeSender()
        r.reset(s1); r.note("a"); flush()
        r.reset(s2); r.note("a"); flush()
        assertThat(s1.sentKeys).containsExactly("a")
        assertThat(s2.sentKeys).containsExactly("a")
    }

    @Test fun lateResultAfterResetChangesNothing() = runTest {
        val r = reporter(); val s1 = FakeSender(); val s2 = FakeSender()
        s1.gate = CompletableDeferred()
        s1.results.add(Result.failure(ZeroSettleError.NetworkError(IOException("x"))))
        r.reset(s1)
        (1..120).forEach { r.note("k$it") }
        flush()
        assertThat(s1.batches).hasSize(1)
        r.reset(s2)
        s1.gate!!.complete(Unit); runCurrent()
        // the old failure must not set a backoff or mark keys, and the old flush sends nothing more
        assertThat(s1.batches).hasSize(1)
        r.note("k1"); flush()
        assertThat(s2.sentKeys).containsExactly("k1")
    }

    @Test fun nothingSentBeforeConfigure() = runTest {
        val r = reporter(); val s = FakeSender()
        r.note("a"); flush()
        r.reset(s); flush()
        assertThat(s.batches).isEmpty()
        r.note("b"); flush()
        assertThat(s.sentKeys).containsExactly("b")
    }

    @Test fun cancelledFlushDoesNotStickTheScheduledFlag() = runTest {
        val r = reporter(); val s = FakeSender(); r.reset(s)
        r.note("a"); runCurrent()
        coroutineContext.job.children.forEach { it.cancel() }
        runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertThat(s.batches).isEmpty()
        r.note("b"); flush()
        assertThat(s.sentKeys).containsExactly("a", "b").inOrder()
    }

    @Test fun keysNotedWhileFlushingAreFlushedNext() = runTest {
        val r = reporter(); val s = FakeSender(); r.reset(s)
        s.gate = CompletableDeferred()
        r.note("a"); flush()
        r.note("b")
        s.gate!!.complete(Unit); runCurrent()
        flush()
        assertThat(s.sentKeys).containsExactly("a", "b").inOrder()
    }

    @Test fun concurrentNotesSendEachKeyOnce() = runTest {
        val r = reporter(); val s = FakeSender(); r.reset(s)
        val start = CountDownLatch(1)
        val threads = (1..2).map {
            Thread { start.await(); (1..100).forEach { i -> r.note("k$i") } }.also { t -> t.start() }
        }
        start.countDown(); threads.forEach { it.join() }
        flush()
        assertThat(s.sentKeys).hasSize(100)
        assertThat(s.sentKeys.toSet()).hasSize(100)
    }

    @Test fun resetDuringFlushStartUsesTheOldSenderOrNone() = runTest {
        val r = reporter(); val s1 = FakeSender("old"); val s2 = FakeSender("new")
        r.reset(s1); r.note("old1"); r.note("old2")
        advanceTimeBy(2_000) // the delay has ended; the flush has not run yet
        r.reset(s2)
        runCurrent()
        assertThat(s2.sentKeys).isEmpty()
        r.note("new1"); flush()
        assertThat(s2.sentKeys).containsExactly("new1")
    }

    @Test fun oldGenerationFinallyDoesNotClearTheNewFlag() = runTest {
        val r = reporter(); val s1 = FakeSender(); val s2 = FakeSender()
        r.reset(s1); r.note("a"); runCurrent()
        s2.gate = CompletableDeferred()
        r.reset(s2); r.note("n1"); runCurrent()
        r.note("n2")
        flush()
        s2.gate!!.complete(Unit); flush()
        assertThat(s2.batches).hasSize(1)
    }

    @Test fun staleFlushDoesNotSendThroughTheNewSender() = runTest {
        val s2 = FakeSender()
        lateinit var r: FeatureCheckReporter
        var first = true
        r = reporter { ms ->
            if (first) { first = false; r.reset(s2); r.note("n1") } else kotlinx.coroutines.delay(ms)
        }
        r.reset(FakeSender()); r.note("old")
        flush(); flush()
        assertThat(s2.sentKeys).containsExactly("n1")
    }
}
