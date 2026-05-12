package com.zerosettle.sdk.billing

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaySyncQueueTest {

    private val queue = PlaySyncQueue(ApplicationProvider.getApplicationContext())

    @Test fun enqueue_thenPending_returnsRow() = runTest {
        queue.clear()
        queue.enqueue(PendingPurchaseSync(purchaseToken = "tok1", productId = "pro", packageName = "com.app", userId = "u1"))
        assertThat(queue.pending()).hasSize(1)
        assertThat(queue.pending().first().purchaseToken).isEqualTo("tok1")
    }

    @Test fun enqueueSameToken_incrementsAttemptCount_noDuplicate() = runTest {
        queue.clear()
        queue.enqueue(PendingPurchaseSync(purchaseToken = "tok1", productId = "pro", packageName = "com.app", userId = "u1"))
        queue.recordFailure("tok1")
        queue.recordFailure("tok1")
        val rows = queue.pending()
        assertThat(rows).hasSize(1)
        assertThat(rows.first().attemptCount).isEqualTo(2)
    }

    @Test fun remove_deletesRow() = runTest {
        queue.clear()
        queue.enqueue(PendingPurchaseSync(purchaseToken = "tok1", productId = "pro", packageName = "com.app", userId = "u1"))
        queue.remove("tok1")
        assertThat(queue.pending()).isEmpty()
    }

    @Test fun backoffDelayMillis_matchesIosSchedule() {
        assertThat(PlaySyncQueue.backoffDelayMillis(attemptCount = 0)).isEqualTo(1_000L)
        assertThat(PlaySyncQueue.backoffDelayMillis(attemptCount = 1)).isEqualTo(5_000L)
        assertThat(PlaySyncQueue.backoffDelayMillis(attemptCount = 2)).isEqualTo(30_000L)
        assertThat(PlaySyncQueue.backoffDelayMillis(attemptCount = 3)).isEqualTo(300_000L)
        assertThat(PlaySyncQueue.backoffDelayMillis(attemptCount = 4)).isNull()
    }

    @Test fun isAbandoned_afterMaxAttempts() = runTest {
        queue.clear()
        queue.enqueue(PendingPurchaseSync(purchaseToken = "tok1", productId = "pro", packageName = "com.app", userId = "u1"))
        repeat(5) { queue.recordFailure("tok1") }
        assertThat(PlaySyncQueue.isAbandoned(queue.pending().first())).isTrue()
    }

    @Test fun clear_emptiesQueue() = runTest {
        queue.enqueue(PendingPurchaseSync(purchaseToken = "tok1", productId = "pro", packageName = "com.app", userId = "u1"))
        queue.clear()
        assertThat(queue.pending()).isEmpty()
    }
}
