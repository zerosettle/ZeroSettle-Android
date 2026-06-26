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
        composeRule.setContent { MaterialTheme { ZeroSettleTheme { ZeroSettleCancelFlow(config = cancelConfig, productId = "test_product", onResult = { }) } } }
        composeRule.onNodeWithText("Why are you leaving?").assertIsDisplayed()
        composeRule.onNodeWithText("Too expensive").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stay for 50% off").assertIsDisplayed()
    }

    @Test fun cancelFlow_acceptSaveOffer_resultIsSaveOfferAccepted() {
        var result: CancelFlow.Result? = null
        composeRule.setContent { MaterialTheme { ZeroSettleTheme { ZeroSettleCancelFlow(config = cancelConfig, productId = "test_product", onResult = { result = it }) } } }
        composeRule.onNodeWithText("Too expensive").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Keep my plan").performClick()
        composeRule.waitForIdle()
        assertTrue(result is CancelFlow.Result.SaveOfferAccepted)
        assertEquals("test_product", (result as CancelFlow.Result.SaveOfferAccepted).productId)
    }

    @Test fun cancelFlow_noQuestions_startsAtSaveOffer() {
        composeRule.setContent {
            MaterialTheme { ZeroSettleTheme { ZeroSettleCancelFlow(config = cancelConfig.copy(questions = emptyList()), productId = "test_product", onResult = { }) } }
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

    // ─── resolveDestination pure logic tests ──────────────────────────────────

    private val enabledOffer = CancelFlow.Offer(enabled = true, title = "Offer")
    private val disabledOffer = CancelFlow.Offer(enabled = false, title = "Offer")
    private val enabledPause = CancelFlow.Pause(enabled = true, title = "Pause")
    private val enabledDeflect = CancelFlow.Deflect(enabled = true, title = "Deflect")
    private val enabledDowngrade = CancelFlow.Downgrade(enabled = true, title = "Downgrade", targetProductId = "pro_monthly")

    private fun option(route: String? = null, triggersOffer: Boolean = false, triggersPause: Boolean = false) =
        CancelFlow.Option(id = 1, order = 0, label = "label", route = route, triggersOffer = triggersOffer, triggersPause = triggersPause)

    private fun config(
        offer: CancelFlow.Offer? = null,
        pause: CancelFlow.Pause? = null,
        deflect: CancelFlow.Deflect? = null,
        downgrade: CancelFlow.Downgrade? = null,
    ) = CancelFlow.Config(enabled = true, offer = offer, pause = pause, deflect = deflect, downgrade = downgrade)

    @Test fun testRoute_offer_withOfferEnabled_returnsOffer() {
        assertEquals(Destination.Offer, resolveDestination(option(route = "offer"), config(offer = enabledOffer)))
    }

    @Test fun testRoute_offer_withOfferDisabled_returnsNextOrFinish() {
        assertEquals(Destination.NextOrFinish, resolveDestination(option(route = "offer"), config(offer = disabledOffer)))
    }

    @Test fun testRoute_offer_withOfferNull_returnsNextOrFinish() {
        assertEquals(Destination.NextOrFinish, resolveDestination(option(route = "offer"), config(offer = null)))
    }

    @Test fun testRoute_pause_withPauseEnabled_returnsPause() {
        assertEquals(Destination.Pause, resolveDestination(option(route = "pause"), config(pause = enabledPause)))
    }

    @Test fun testRoute_pause_withPauseDisabled_returnsNextOrFinish() {
        assertEquals(Destination.NextOrFinish, resolveDestination(option(route = "pause"), config(pause = null)))
    }

    @Test fun testRoute_deflect_withDeflectEnabled_returnsDeflect() {
        assertEquals(Destination.Deflect, resolveDestination(option(route = "deflect"), config(deflect = enabledDeflect)))
    }

    @Test fun testRoute_deflect_withDeflectNil_returnsNextOrFinish() {
        assertEquals(Destination.NextOrFinish, resolveDestination(option(route = "deflect"), config(deflect = null)))
    }

    @Test fun testRoute_downgrade_withDowngradeEnabled_returnsDowngrade() {
        assertEquals(Destination.Downgrade, resolveDestination(option(route = "downgrade"), config(downgrade = enabledDowngrade)))
    }

    @Test fun testRoute_downgrade_withDowngradeNil_returnsNextOrFinish() {
        assertEquals(Destination.NextOrFinish, resolveDestination(option(route = "downgrade"), config(downgrade = null)))
    }

    @Test fun testRoute_none_returnsNextOrFinish() {
        assertEquals(Destination.NextOrFinish, resolveDestination(option(route = "none"), config(offer = enabledOffer, pause = enabledPause)))
    }

    @Test fun testRoute_unknown_returnsNextOrFinish() {
        assertEquals(Destination.NextOrFinish, resolveDestination(option(route = "xyz_unknown"), config(offer = enabledOffer)))
    }

    @Test fun testNilRoute_triggersOffer_returnsOffer() {
        assertEquals(Destination.Offer, resolveDestination(option(triggersOffer = true), config(offer = enabledOffer)))
    }

    @Test fun testNilRoute_triggersPause_returnsPause() {
        assertEquals(Destination.Pause, resolveDestination(option(triggersPause = true), config(pause = enabledPause)))
    }

    @Test fun testNilRoute_triggersBoth_returnsBothOfferAndPause() {
        assertEquals(
            Destination.BothOfferAndPause,
            resolveDestination(option(triggersOffer = true, triggersPause = true), config(offer = enabledOffer, pause = enabledPause)),
        )
    }

    @Test fun testNilRoute_triggersNeither_returnsNextOrFinish() {
        assertEquals(Destination.NextOrFinish, resolveDestination(option(), config(offer = enabledOffer, pause = enabledPause)))
    }

    @Test fun testNilRoute_triggersOffer_offerDisabled_returnsNextOrFinish() {
        assertEquals(Destination.NextOrFinish, resolveDestination(option(triggersOffer = true), config(offer = disabledOffer)))
    }
}
