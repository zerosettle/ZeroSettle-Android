package com.zerosettle.sdk.access

import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sends one batch of feature keys for one publishable key. Built per key by [FeatureCheckReporter.reset]. */
internal fun interface FeatureCheckSender {
    suspend fun send(keys: List<String>): Result<Unit>
}

/**
 * Reports the feature keys the app checks (`hasAccess` / `value`) to `POST /v1/iap/features/checked/`,
 * so the dashboard's "Check it in your app" step can complete.
 *
 * A key goes out at most once per process per publishable key, batched 50 per request, from a
 * flush scheduled [flushDelayMs] after a check. Everything is guarded by one lock because
 * `hasAccess` is called from any thread; the network work runs on [scope], never on the caller.
 * A [reset] (new publishable key or API base URL) bumps [generation], so a late result from the
 * old key changes nothing.
 */
internal class FeatureCheckReporter(
    private val scope: CoroutineScope,
    private val flushDelayMs: Long = FLUSH_DELAY_MS,
    private val backoffMs: Long = BACKOFF_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private val sent = HashSet<String>()
    private val pending = LinkedHashSet<String>()
    private var generation = 0
    private var scheduled = false
    private var job: Job? = null
    private var backoffUntil = 0L
    private var sender: FeatureCheckSender? = null

    /** Records a checked key. Never blocks, never throws. Dropped before the first [reset]. */
    fun note(key: String) {
        if (key.isEmpty() || key.codePointCount(0, key.length) > MAX_KEY_CODE_POINTS) return
        try {
            synchronized(lock) {
                if (sender == null || key in sent) return
                pending.add(key)
                scheduleLocked()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        }
    }

    /** Installs the sender for a new publishable key and forgets everything about the old one. */
    fun reset(sender: FeatureCheckSender) {
        synchronized(lock) {
            generation++
            scheduled = false
            job?.cancel()
            job = null
            sent.clear()
            pending.clear()
            backoffUntil = 0L
            this.sender = sender
        }
    }

    private fun scheduleLocked() {
        if (scheduled || clock() < backoffUntil) return
        scheduled = true
        val gen = generation
        job = scope.launch {
            var failed = true
            try {
                delay(flushDelayMs)
                failed = flush(gen)
            } finally {
                synchronized(lock) {
                    if (generation == gen) {
                        scheduled = false
                        if (!failed && pending.isNotEmpty()) scheduleLocked()
                    }
                }
            }
        }
    }

    /** Sends pending batches in order. Returns true when it stopped on a failure or a stale generation. */
    private suspend fun flush(gen: Int): Boolean {
        while (true) {
            val batch: List<String>
            val batchSender: FeatureCheckSender
            synchronized(lock) {
                if (generation != gen) return true
                val s = sender ?: return true
                if (pending.isEmpty()) return false
                batch = pending.take(BATCH_SIZE)
                batchSender = s
            }
            val result = try {
                batchSender.send(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            synchronized(lock) {
                if (generation != gen) return true
                if (result.isSuccess || result.isTerminal()) {
                    sent.addAll(batch)
                    pending.removeAll(batch.toSet())
                } else {
                    backoffUntil = clock() + backoffMs
                    return true
                }
            }
        }
    }

    // A rejected request cannot succeed on retry, so its keys count as sent.
    private fun Result<Unit>.isTerminal(): Boolean {
        val err = exceptionOrNull() as? ZeroSettleError.BackendError ?: return false
        return err.statusCode in TERMINAL_STATUSES
    }

    companion object {
        const val FLUSH_DELAY_MS = 2_000L
        const val BACKOFF_MS = 60_000L
        const val BATCH_SIZE = 50
        const val MAX_KEY_CODE_POINTS = 64
        private val TERMINAL_STATUSES = setOf(400, 401, 403)
    }
}
