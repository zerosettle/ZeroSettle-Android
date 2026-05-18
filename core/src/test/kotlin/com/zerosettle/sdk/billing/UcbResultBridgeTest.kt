package com.zerosettle.sdk.billing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Phase 2 Chunk D: [UcbResultBridge] composes the activity-emitted
 * [PaymentSheetStatus] with the IDs the launcher reserved from `/initiate/`
 * into the final [UcbPurchaseOutcome] handed to the suspending caller.
 *
 *   reserve(extId, txnId) → deferred
 *      → activity calls deliver(PaymentSheetStatus.Completed)
 *      → bridge composes UcbPurchaseOutcome.Completed(extId, txnId)
 *      → deferred.await() resumes the launcher with the composed outcome
 *
 * Single-flight: a second reserve while one is armed completes the stale
 * one with `Failed` (no leaks). `deliver` is a no-op when nothing is armed
 * (defends against rotation-recreated activity delivering twice).
 */
class UcbResultBridgeTest {

    @Before fun setUp() { UcbResultBridge.reset() }
    @After fun tearDown() { UcbResultBridge.reset() }

    @Test fun completed_status_composesWithReservedIds() = runTest {
        val pending = UcbResultBridge.reserve(
            externalTransactionId = "ext_abc",
            transactionId = 7L,
        )
        UcbResultBridge.deliver(PaymentSheetStatus.Completed)

        val outcome = pending.await()
        assertThat(outcome).isInstanceOf(UcbPurchaseOutcome.Completed::class.java)
        val completed = outcome as UcbPurchaseOutcome.Completed
        assertThat(completed.externalTransactionId).isEqualTo("ext_abc")
        assertThat(completed.transactionId).isEqualTo(7L)
    }

    @Test fun completed_status_carriesNullTransactionId_whenReservedNull() = runTest {
        // The backend's /initiate/ schema declares transaction_id as nullable.
        // When the backend returns null, the launcher reserves with null and
        // the composed Completed must reflect that.
        val pending = UcbResultBridge.reserve(
            externalTransactionId = "ext_xyz",
            transactionId = null,
        )
        UcbResultBridge.deliver(PaymentSheetStatus.Completed)

        val outcome = pending.await() as UcbPurchaseOutcome.Completed
        assertThat(outcome.externalTransactionId).isEqualTo("ext_xyz")
        assertThat(outcome.transactionId).isNull()
    }

    @Test fun canceled_status_resolvesAsCanceledOutcome() = runTest {
        val pending = UcbResultBridge.reserve("e", 1L)
        UcbResultBridge.deliver(PaymentSheetStatus.Canceled)

        assertThat(pending.await()).isEqualTo(UcbPurchaseOutcome.Canceled)
    }

    @Test fun failed_status_resolvesAsFailedOutcomeWithMessage() = runTest {
        val pending = UcbResultBridge.reserve("e", 1L)
        UcbResultBridge.deliver(PaymentSheetStatus.Failed("card_declined"))

        val outcome = pending.await()
        assertThat(outcome).isInstanceOf(UcbPurchaseOutcome.Failed::class.java)
        assertThat((outcome as UcbPurchaseOutcome.Failed).message).isEqualTo("card_declined")
    }

    @Test fun secondReserve_completesStaleWithFailed_andArmsFresh() = runTest {
        // Single-flight: only one outcome can be in-flight at a time. A
        // second reserve while one is armed must complete the stale
        // deferred with Failed (preventing a leaked awaiter) and arm a
        // fresh one for the new caller.
        val stale = UcbResultBridge.reserve("ext_stale", 100L)
        val fresh = UcbResultBridge.reserve("ext_fresh", 200L)

        // Stale was completed at the moment of re-reserve.
        assertThat(stale.isCompleted).isTrue()
        assertThat(stale.await()).isInstanceOf(UcbPurchaseOutcome.Failed::class.java)

        UcbResultBridge.deliver(PaymentSheetStatus.Completed)
        val freshOutcome = fresh.await() as UcbPurchaseOutcome.Completed
        // The fresh outcome carries the second-reserve IDs, not the stale ones.
        assertThat(freshOutcome.externalTransactionId).isEqualTo("ext_fresh")
        assertThat(freshOutcome.transactionId).isEqualTo(200L)
    }

    @Test fun deliver_withoutReserve_isNoOp() {
        // Activity delivers after the awaiter is already gone (e.g.,
        // rotation-recreated activity races a completed launcher). The
        // bridge silently absorbs the delivery; no exception, no leak.
        UcbResultBridge.deliver(PaymentSheetStatus.Completed)
        assertThat(UcbResultBridge.isReservedForTest()).isFalse()
    }

    @Test fun reset_completesPending_andDisarms() = runTest {
        val pending = UcbResultBridge.reserve("e", 1L)
        UcbResultBridge.reset()
        assertThat(pending.isCompleted).isTrue()
        assertThat(pending.await()).isInstanceOf(UcbPurchaseOutcome.Failed::class.java)
        assertThat(UcbResultBridge.isReservedForTest()).isFalse()
    }
}
