package com.zerosettle.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.zerosettle.ui.theme.ZeroSettleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZeroSettleCheckoutSheetTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun rendersWebViewContainer_whenUrlPresent() {
        composeRule.setContent {
            MaterialTheme {
                ZeroSettleTheme {
                    ZeroSettleCheckoutSheet(checkoutUrl = "https://checkout.zerosettle.com/c/abc", onResult = { })
                }
            }
        }
        composeRule.onNodeWithTag("zerosettle_checkout_webview").assertExists()
    }

    @Test fun classifyNavigation_successCallback_emitsSucceeded() {
        val r = ZeroSettleCheckoutSheetNav.classifyNavigation("zerosettle://checkout/return?status=success&transaction_id=txn_9")
        assertTrue(r is ZeroSettleCheckoutSheetNav.NavResult.Succeeded)
        assertEquals("txn_9", (r as ZeroSettleCheckoutSheetNav.NavResult.Succeeded).transactionId)
    }

    @Test fun classifyNavigation_cancelCallback_emitsCancelled() {
        assertTrue(ZeroSettleCheckoutSheetNav.classifyNavigation("zerosettle://checkout/return?status=cancelled") is ZeroSettleCheckoutSheetNav.NavResult.Cancelled)
    }

    @Test fun classifyNavigation_failedCallback_emitsFailedWithReason() {
        val r = ZeroSettleCheckoutSheetNav.classifyNavigation("zerosettle://checkout/return?status=failed&reason=card_declined")
        assertTrue(r is ZeroSettleCheckoutSheetNav.NavResult.Failed)
        assertEquals("card_declined", (r as ZeroSettleCheckoutSheetNav.NavResult.Failed).reason)
    }

    @Test fun classifyNavigation_unrelatedUrl_isPassthrough() {
        assertTrue(ZeroSettleCheckoutSheetNav.classifyNavigation("https://js.stripe.com/v3/") is ZeroSettleCheckoutSheetNav.NavResult.Continue)
    }
}
