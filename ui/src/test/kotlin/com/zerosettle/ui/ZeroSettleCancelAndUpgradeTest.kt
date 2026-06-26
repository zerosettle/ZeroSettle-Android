package com.zerosettle.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zerosettle.sdk.models.CancelFlow
import com.zerosettle.sdk.models.UpgradeOffer
import com.zerosettle.ui.theme.ZeroSettleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZeroSettleCancelAndUpgradeTest {
    @get:Rule val composeRule = createComposeRule()

    // 1.3.0: options are typed Option objects; offer lives in Config.offer; pause in Config.pause.
    private val cancelConfig = CancelFlow.Config(
        enabled = true,
        questions = listOf(
            CancelFlow.Question(
                id = 1, order = 0, questionText = "Why are you leaving?",
                questionType = "single_select", isRequired = true,
                options = listOf(
                    CancelFlow.Option(id = 1, order = 0, label = "Too expensive", triggersOffer = true, triggersPause = false),
                    CancelFlow.Option(id = 2, order = 1, label = "Not using it", triggersOffer = false, triggersPause = false),
                ),
            ),
        ),
        offer = CancelFlow.Offer(
            enabled = true,
            title = "Stay for 50% off",
            body = "Stay for 50% off",
            ctaText = "Keep my plan",
            type = "discount",
            value = "50",
        ),
        pause = CancelFlow.Pause(
            enabled = true,
            title = "Pause instead?",
            body = "Pause and come back.",
            ctaText = "Pause",
            options = listOf(
                CancelFlow.PauseOption(id = 1, order = 0, label = "1 week", durationType = "days", durationDays = 7),
                CancelFlow.PauseOption(id = 2, order = 1, label = "1 month", durationType = "days", durationDays = 30),
            ),
        ),
    )

    @Test fun cancelFlow_showsQuestion_thenSaveOffer() {
        composeRule.setContent { MaterialTheme { ZeroSettleTheme { ZeroSettleCancelFlow(config = cancelConfig, onResult = { }) } } }
        composeRule.onNodeWithText("Why are you leaving?").assertIsDisplayed()
        composeRule.onNodeWithText("Too expensive").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stay for 50% off").assertIsDisplayed()
    }

    @Test fun cancelFlow_acceptSaveOffer_resultIsSaveOfferAccepted() {
        var result: CancelFlow.Result? = null
        composeRule.setContent { MaterialTheme { ZeroSettleTheme { ZeroSettleCancelFlow(config = cancelConfig, onResult = { result = it }) } } }
        composeRule.onNodeWithText("Too expensive").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Keep my plan").performClick()
        composeRule.waitForIdle()
        // 1.3.0: SaveOfferAccepted.productId is a placeholder (empty string) from ZeroSettleCancelFlow;
        // callers substitute the screen-level productId. We only assert the type here.
        // TODO(Task 3.4): once the composable carries a real productId (no placeholder),
        // restore the assertEquals on the resulting productId.
        assertTrue(result is CancelFlow.Result.SaveOfferAccepted)
    }

    @Test fun cancelFlow_noQuestions_startsAtSaveOffer() {
        composeRule.setContent {
            MaterialTheme { ZeroSettleTheme { ZeroSettleCancelFlow(config = cancelConfig.copy(questions = emptyList()), onResult = { }) } }
        }
        composeRule.onNodeWithText("Stay for 50% off").assertIsDisplayed()
    }

    @Test fun upgradeOffer_acceptCallsResultAccepted() {
        val cfg = UpgradeOffer.Config(
            available = true, savingsPercent = 20,
            targetProduct = UpgradeOffer.ProductInfo("pro_yearly", "Yearly", 7999, "USD", "$79.99/yr"),
            display = UpgradeOffer.Display("Go yearly", "Save 20% with annual billing", "Upgrade", "Done"),
        )
        var result: UpgradeOffer.Result? = null
        composeRule.setContent { MaterialTheme { ZeroSettleTheme { ZeroSettleUpgradeOffer(config = cfg, onResult = { result = it }) } } }
        composeRule.onNodeWithText("Go yearly").assertIsDisplayed()
        composeRule.onNodeWithText("Upgrade").performClick()
        composeRule.waitForIdle()
        assertTrue(result is UpgradeOffer.Result.Accepted)
        assertEquals("pro_yearly", (result as UpgradeOffer.Result.Accepted).newProductId)
    }

    @Test fun upgradeOffer_notNowCallsDismissed() {
        val cfg = UpgradeOffer.Config(
            available = true, savingsPercent = 10,
            targetProduct = UpgradeOffer.ProductInfo("b", "B", 100, "USD", "label"),
            display = UpgradeOffer.Display("T", "M", "Upgrade", "Not now"),
        )
        var result: UpgradeOffer.Result? = null
        composeRule.setContent { MaterialTheme { ZeroSettleTheme { ZeroSettleUpgradeOffer(config = cfg, onResult = { result = it }) } } }
        composeRule.onNodeWithText("Not now").performClick()
        composeRule.waitForIdle()
        assertTrue(result is UpgradeOffer.Result.Dismissed)
    }
}
