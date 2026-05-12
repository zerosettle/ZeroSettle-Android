package com.zerosettle.sdk.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PendingActionTest {
    @Test fun exhaustiveWhen_compilesForBothVariants() {
        val actions: List<PendingAction> = listOf(
            PendingAction.MigrationCompletedInfo(
                transactionId = "txn_1", userMessage = "Switched.",
                playAccessEndsAtIso = "2026-06-01T00:00:00Z", newSubscriptionPriceCents = 499,
                newSubscriptionCurrency = "USD", newSubscriptionInterval = "month",
            ),
            PendingAction.ManualPlayCancel(
                transactionId = "txn_2", userMessage = "Cancel it now.",
                originalPlayPurchaseToken = "ptok_x", expiresAtIso = "2026-06-01T00:00:00Z",
                deepLink = "https://play.google.com/store/account/subscriptions?package=com.app",
            ),
        )
        actions.forEach { a ->
            val tag: String = when (a) {
                is PendingAction.MigrationCompletedInfo -> "info:${a.newSubscriptionPriceCents}"
                is PendingAction.ManualPlayCancel -> "cancel:${a.deepLink}"
            }
            assertThat(tag).isNotEmpty()
            assertThat(a.transactionId).isNotEmpty()
            assertThat(a.userMessage).isNotEmpty()
        }
    }

    @Test fun migrationCompletedInfo_allowsNullableOptionalFields() {
        val a = PendingAction.MigrationCompletedInfo(
            transactionId = "txn_1", userMessage = "m",
            playAccessEndsAtIso = null, newSubscriptionPriceCents = null,
            newSubscriptionCurrency = null, newSubscriptionInterval = null,
        )
        assertThat(a.playAccessEndsAtIso).isNull()
        assertThat(a.newSubscriptionPriceCents).isNull()
    }
}
