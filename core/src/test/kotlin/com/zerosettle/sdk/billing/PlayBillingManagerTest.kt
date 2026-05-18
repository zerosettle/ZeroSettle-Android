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

    // ─── classifyPurchaseListenerResult — cancel-hang fix ────────────────────
    //
    // The PurchasesUpdatedListener used to silently swallow USER_CANCELED and
    // merely log other non-OK codes, stranding any in-flight
    // purchaseViaPlayBilling() deferred. The classifier now routes every
    // terminal (non-deliverable) result into a Fail outcome so the listener
    // can resolve the deferred via onPurchaseFailed.

    @Test fun classify_okWithPurchases_isDeliver() {
        val json = """{"productId":"p","purchaseToken":"tok","purchaseState":0,"packageName":"com.app"}"""
        val purchases = listOf(com.android.billingclient.api.Purchase(json, "sig"))
        val outcome = classifyPurchaseListenerResult(
            BillingClient.BillingResponseCode.OK, "", purchases,
        )
        assertThat(outcome).isInstanceOf(PurchaseListenerOutcome.Deliver::class.java)
        assertThat((outcome as PurchaseListenerOutcome.Deliver).purchases).isEqualTo(purchases)
    }

    @Test fun classify_userCanceled_isFailWithPurchaseCancelled() {
        val outcome = classifyPurchaseListenerResult(
            BillingClient.BillingResponseCode.USER_CANCELED, "user dismissed", null,
        )
        assertThat(outcome).isInstanceOf(PurchaseListenerOutcome.Fail::class.java)
        assertThat((outcome as PurchaseListenerOutcome.Fail).error)
            .isEqualTo(ZeroSettleError.PurchaseCancelled)
    }

    @Test fun classify_okWithNullPurchases_isFailWithPurchaseCancelled() {
        // OK but nothing delivered — must still resolve an armed deferred.
        val outcome = classifyPurchaseListenerResult(
            BillingClient.BillingResponseCode.OK, "", null,
        )
        assertThat(outcome).isInstanceOf(PurchaseListenerOutcome.Fail::class.java)
        assertThat((outcome as PurchaseListenerOutcome.Fail).error)
            .isEqualTo(ZeroSettleError.PurchaseCancelled)
    }

    @Test fun classify_okWithEmptyPurchases_isFailWithPurchaseCancelled() {
        val outcome = classifyPurchaseListenerResult(
            BillingClient.BillingResponseCode.OK, "", emptyList(),
        )
        assertThat(outcome).isInstanceOf(PurchaseListenerOutcome.Fail::class.java)
        assertThat((outcome as PurchaseListenerOutcome.Fail).error)
            .isEqualTo(ZeroSettleError.PurchaseCancelled)
    }

    @Test fun classify_itemUnavailable_isFailWithPlayBillingError() {
        val outcome = classifyPurchaseListenerResult(
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE, "gone", null,
        )
        assertThat(outcome).isInstanceOf(PurchaseListenerOutcome.Fail::class.java)
        val err = (outcome as PurchaseListenerOutcome.Fail).error
        assertThat(err).isInstanceOf(ZeroSettleError.PlayBillingError::class.java)
        assertThat((err as ZeroSettleError.PlayBillingError).responseCode)
            .isEqualTo(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)
    }

    @Test fun classify_serviceUnavailable_isFailWithPlayBillingError() {
        val outcome = classifyPurchaseListenerResult(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "down", null,
        )
        assertThat(outcome).isInstanceOf(PurchaseListenerOutcome.Fail::class.java)
        assertThat((outcome as PurchaseListenerOutcome.Fail).error)
            .isInstanceOf(ZeroSettleError.PlayBillingError::class.java)
    }

    @Test fun classify_developerError_isFailWithPlayBillingError() {
        val outcome = classifyPurchaseListenerResult(
            BillingClient.BillingResponseCode.DEVELOPER_ERROR, "bad params", null,
        )
        assertThat(outcome).isInstanceOf(PurchaseListenerOutcome.Fail::class.java)
        assertThat((outcome as PurchaseListenerOutcome.Fail).error)
            .isInstanceOf(ZeroSettleError.PlayBillingError::class.java)
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
