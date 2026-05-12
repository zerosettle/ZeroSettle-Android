package com.zerosettle.sdk.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ZeroSettleEventTest {
    @Test fun whenExpression_compilesForEveryCase() {
        val events: List<ZeroSettleEvent> = listOf(
            ZeroSettleEvent.OfferShown(productId = "p"),
            ZeroSettleEvent.OfferAccepted(productId = "p"),
            ZeroSettleEvent.OfferDismissed(productId = "p"),
            ZeroSettleEvent.PurchaseSucceeded(productId = "p", transactionId = "t"),
            ZeroSettleEvent.PurchaseFailed(productId = "p", reason = "r"),
            ZeroSettleEvent.MigrationCompleted(productId = "p"),
            ZeroSettleEvent.SyncFailed(purchaseToken = "t", attempts = 5, terminal = true),
            ZeroSettleEvent.EntitlementsRefreshed(count = 2),
            ZeroSettleEvent.PendingActionShown(actionType = "manual_play_cancel"),
            ZeroSettleEvent.OfferEvaluationFailed(reason = "boom"),
        )
        events.forEach { e ->
            val tag: String = when (e) {
                is ZeroSettleEvent.OfferShown -> "os"
                is ZeroSettleEvent.OfferAccepted -> "oa"
                is ZeroSettleEvent.OfferDismissed -> "od"
                is ZeroSettleEvent.PurchaseSucceeded -> "ps"
                is ZeroSettleEvent.PurchaseFailed -> "pf"
                is ZeroSettleEvent.MigrationCompleted -> "mc"
                is ZeroSettleEvent.SyncFailed -> "sf"
                is ZeroSettleEvent.EntitlementsRefreshed -> "er"
                is ZeroSettleEvent.PendingActionShown -> "pa"
                is ZeroSettleEvent.OfferEvaluationFailed -> "ef"
            }
            assertThat(tag).isNotEmpty()
        }
    }
}
