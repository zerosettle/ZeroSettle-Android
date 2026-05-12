package com.zerosettle.sdk.billing

import com.android.billingclient.api.BillingClient
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.ZeroSettleError
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayBillingManagerTest {

    @Test fun mapBillingError_userCancelled() {
        val e = PlayBillingManager.mapBillingError(BillingClient.BillingResponseCode.USER_CANCELED, "x")
        assertThat(e).isEqualTo(ZeroSettleError.PurchaseCancelled)
    }

    @Test fun mapBillingError_serviceUnavailable_isPlayBillingError() {
        val e = PlayBillingManager.mapBillingError(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "down")
        assertThat(e).isInstanceOf(ZeroSettleError.PlayBillingError::class.java)
        assertThat((e as ZeroSettleError.PlayBillingError).responseCode).isEqualTo(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
    }

    @Test fun describePurchase_extractsFields() {
        val originalJson = """
        {"orderId":"GPA.1234","packageName":"com.app","productId":"pro_monthly",
         "purchaseTime":1700000000000,"purchaseState":0,"purchaseToken":"tok-abc",
         "autoRenewing":true,"acknowledged":false}
        """.trimIndent()
        val purchase = com.android.billingclient.api.Purchase(originalJson, "sig-xyz")
        val d = PlayBillingManager.describePurchase(
            purchase, userId = "u1", customerName = "Al", customerEmail = "a@x",
        )
        assertThat(d.purchaseToken).isEqualTo("tok-abc")
        assertThat(d.productId).isEqualTo("pro_monthly")
        assertThat(d.packageName).isEqualTo("com.app")
        assertThat(d.orderId).isEqualTo("GPA.1234")
        assertThat(d.willAutoRenew).isTrue()
        assertThat(d.purchaseState).isEqualTo(com.android.billingclient.api.Purchase.PurchaseState.PURCHASED)
        assertThat(d.userId).isEqualTo("u1")
        assertThat(d.customerName).isEqualTo("Al")
    }
}
