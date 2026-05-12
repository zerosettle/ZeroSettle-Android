package com.zerosettle.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zerosettle.sdk.models.CancelFlow
import com.zerosettle.sdk.models.Offer
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

    private val cancelConfig = CancelFlow.Config(
        questions = listOf(CancelFlow.Question(id = "q1", prompt = "Why are you leaving?", options = listOf("Too expensive", "Not using it"))),
        saveOffer = CancelFlow.SaveOffer(productId = "pro_monthly", savingsPercent = 50, copy = "Stay for 50% off"),
        pauseOptionsDays = listOf(7, 30),
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
        assertTrue(result is CancelFlow.Result.SaveOfferAccepted)
        assertEquals("pro_monthly", (result as CancelFlow.Result.SaveOfferAccepted).productId)
    }

    @Test fun cancelFlow_noQuestions_startsAtSaveOffer() {
        composeRule.setContent {
            MaterialTheme { ZeroSettleTheme { ZeroSettleCancelFlow(config = cancelConfig.copy(questions = emptyList()), onResult = { }) } }
        }
        composeRule.onNodeWithText("Stay for 50% off").assertIsDisplayed()
    }

    @Test fun upgradeOffer_acceptCallsResultAccepted() {
        val cfg = UpgradeOffer.Config(
            fromProductId = "pro_monthly", toProductId = "pro_yearly", savingsPercent = 20,
            display = Offer.OfferDisplay("Go yearly", "Save 20% with annual billing", "Upgrade", "Done", "", "", "", ""),
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
            fromProductId = "a", toProductId = "b", savingsPercent = 10,
            display = Offer.OfferDisplay("T", "M", "Upgrade", "", "", "", "", ""),
        )
        var result: UpgradeOffer.Result? = null
        composeRule.setContent { MaterialTheme { ZeroSettleTheme { ZeroSettleUpgradeOffer(config = cfg, onResult = { result = it }) } } }
        composeRule.onNodeWithText("Not now").performClick()
        composeRule.waitForIdle()
        assertTrue(result is UpgradeOffer.Result.Dismissed)
    }
}
