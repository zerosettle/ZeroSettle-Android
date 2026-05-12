package com.zerosettle.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zerosettle.sdk.models.Offer
import com.zerosettle.sdk.offers.OfferManager
import com.zerosettle.ui.theme.ZeroSettleTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZeroSettleOfferTipTest {
    @get:Rule val composeRule = createComposeRule()

    private val migrationOffer = Offer.OfferData(
        flowType = Offer.FlowType.MIGRATION, upgradeType = null,
        sourceStorefront = Offer.SourceStorefront.PLAY_STORE,
        productId = "pro_monthly", eligibleProductIds = listOf("pro_monthly"),
        savingsPercent = 20,
        display = Offer.OfferDisplay(
            "Save 20%", "Switch to direct billing", "Switch now",
            "Almost done", "Finishing up", "Continue", "All set!", "Welcome",
        ),
        freeTrialDays = 7, minSubscriptionDays = 14, maxSubscriptionDays = null,
        rolloutPercent = 100, checkoutPresentation = Offer.CheckoutPresentation.CUSTOM_TAB,
    )

    private fun set(state: OfferManager.OfferState, onAccept: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme {
                ZeroSettleTheme {
                    ZeroSettleOfferTipContent(state = state, offer = migrationOffer, onAccept = onAccept, onDismiss = onDismiss)
                }
            }
        }
    }

    @Test fun rendersOfferCard_whenPresented() {
        set(OfferManager.OfferState.PRESENTED)
        composeRule.onNodeWithText("Save 20%").assertIsDisplayed()
        composeRule.onNodeWithText("Switch now").assertIsDisplayed()
        composeRule.onNodeWithText("Not now").assertIsDisplayed()
    }

    @Test fun rendersNothing_whenIneligible() {
        set(OfferManager.OfferState.INELIGIBLE)
        composeRule.onAllNodesWithText("Switch now").assertCountEquals(0)
    }

    @Test fun rendersNothing_whenLoading() {
        set(OfferManager.OfferState.LOADING)
        composeRule.onAllNodesWithText("Switch now").assertCountEquals(0)
    }

    @Test fun acceptedState_showsAlmostDone() {
        set(OfferManager.OfferState.ACCEPTED)
        composeRule.onNodeWithText("Almost done").assertIsDisplayed()
        composeRule.onAllNodesWithText("Switch now").assertCountEquals(0)
    }

    @Test fun completedState_showsCongratulations() {
        set(OfferManager.OfferState.COMPLETED)
        composeRule.onNodeWithText("All set!").assertIsDisplayed()
    }

    @Test fun tappingCta_invokesOnAccept() {
        var accepted = false
        set(OfferManager.OfferState.PRESENTED, onAccept = { accepted = true })
        composeRule.onNodeWithText("Switch now").performClick()
        composeRule.waitForIdle()
        assertTrue(accepted)
    }

    @Test fun tappingNotNow_invokesOnDismiss() {
        var dismissed = false
        set(OfferManager.OfferState.PRESENTED, onDismiss = { dismissed = true })
        composeRule.onNodeWithText("Not now").performClick()
        composeRule.waitForIdle()
        assertTrue(dismissed)
    }
}
