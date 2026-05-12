package com.zerosettle.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zerosettle.sdk.models.PendingAction
import com.zerosettle.ui.theme.ZeroSettleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZeroSettlePendingActionBannerTest {
    @get:Rule val composeRule = createComposeRule()

    private fun set(action: PendingAction, onDeepLink: (String) -> Unit = {}, onDismiss: (PendingAction) -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme {
                ZeroSettleTheme { ZeroSettlePendingActionBanner(action = action, onDeepLink = onDeepLink, onDismiss = onDismiss) }
            }
        }
    }

    @Test fun manualPlayCancel_showsMessageAndDeepLinkButton() {
        set(
            PendingAction.ManualPlayCancel(
                transactionId = "t1", userMessage = "We couldn't cancel your Play subscription.",
                originalPlayPurchaseToken = "p", expiresAtIso = "2026-06-01T00:00:00Z",
                deepLink = "https://play.google.com/store/account/subscriptions?package=com.app",
            ),
        )
        composeRule.onNodeWithText("We couldn't cancel your Play subscription.").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel on Google Play").assertIsDisplayed()
        composeRule.onNodeWithText("Later").assertIsDisplayed()
    }

    @Test fun manualPlayCancel_deepLinkButtonCallsOnDeepLink() {
        var url: String? = null
        set(
            PendingAction.ManualPlayCancel(
                transactionId = "t1", userMessage = "msg", originalPlayPurchaseToken = "p",
                expiresAtIso = null, deepLink = "https://play.google.com/subs",
            ),
            onDeepLink = { url = it },
        )
        composeRule.onNodeWithText("Cancel on Google Play").performClick()
        composeRule.waitForIdle()
        assertEquals("https://play.google.com/subs", url)
    }

    @Test fun migrationCompletedInfo_showsMessage_dismissCallsCallback() {
        val action = PendingAction.MigrationCompletedInfo(
            transactionId = "t2", userMessage = "You switched to web checkout.",
            playAccessEndsAtIso = "2026-06-01T00:00:00Z", newSubscriptionPriceCents = 499,
            newSubscriptionCurrency = "USD", newSubscriptionInterval = "month",
        )
        var dismissed: PendingAction? = null
        set(action, onDismiss = { dismissed = it })
        composeRule.onNodeWithText("You switched to web checkout.").assertIsDisplayed()
        composeRule.onNodeWithText("Got it").performClick()
        composeRule.waitForIdle()
        assertEquals(action, dismissed)
    }
}
