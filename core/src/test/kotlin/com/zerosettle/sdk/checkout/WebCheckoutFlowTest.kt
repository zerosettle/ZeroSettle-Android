package com.zerosettle.sdk.checkout

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WebCheckoutFlowTest {

    @Test fun parseCallback_successUrl_isSuccess() {
        val r = WebCheckoutFlow.parseCallback("zerosettle://checkout/return?status=success&transaction_id=txn_1")
        assertThat(r).isInstanceOf(WebCheckoutFlow.CallbackResult.Succeeded::class.java)
        assertThat((r as WebCheckoutFlow.CallbackResult.Succeeded).transactionId).isEqualTo("txn_1")
    }

    @Test fun parseCallback_cancelUrl_isCancelled() {
        val r = WebCheckoutFlow.parseCallback("zerosettle://checkout/return?status=cancelled")
        assertThat(r).isEqualTo(WebCheckoutFlow.CallbackResult.Cancelled)
    }

    @Test fun parseCallback_failureUrl_carriesReason() {
        val r = WebCheckoutFlow.parseCallback("zerosettle://checkout/return?status=failed&reason=card_declined")
        assertThat(r).isInstanceOf(WebCheckoutFlow.CallbackResult.Failed::class.java)
        assertThat((r as WebCheckoutFlow.CallbackResult.Failed).reason).isEqualTo("card_declined")
    }

    @Test fun parseCallback_unrelatedUrl_isNull() {
        assertThat(WebCheckoutFlow.parseCallback("https://example.com/foo")).isNull()
    }

    @Test fun isCallbackUrl_matchesScheme() {
        assertThat(WebCheckoutFlow.isCallbackUrl("zerosettle://checkout/return?x=1")).isTrue()
        assertThat(WebCheckoutFlow.isCallbackUrl("https://api.zerosettle.io/checkout")).isFalse()
    }

    @Test fun launchWebView_startsActivityWithCheckoutUrlExtra() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        WebCheckoutFlow.launchWebView(activity, "https://checkout.example/abc")
        val started = shadowOf(activity).nextStartedActivity
        assertThat(started).isNotNull()
        assertThat(started.component?.className).isEqualTo(ZeroSettleWebViewActivity::class.java.name)
        assertThat(started.getStringExtra(ZeroSettleWebViewActivity.EXTRA_CHECKOUT_URL))
            .isEqualTo("https://checkout.example/abc")
    }
}
