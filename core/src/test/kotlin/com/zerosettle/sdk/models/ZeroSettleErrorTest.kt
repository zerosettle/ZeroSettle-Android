package com.zerosettle.sdk.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ZeroSettleErrorTest {
    @Test fun exhaustiveWhen_compilesForAllCases() {
        val cases: List<ZeroSettleError> = listOf(
            ZeroSettleError.NotConfigured,
            ZeroSettleError.UserNotIdentified,
            ZeroSettleError.UserIdRequired,
            ZeroSettleError.InvalidUserId("blank"),
            ZeroSettleError.CheckoutFailed("user_cancelled"),
            ZeroSettleError.PurchaseCancelled,
            ZeroSettleError.PurchasePending,
            ZeroSettleError.PlayBillingError(responseCode = 6, debugMessage = "x"),
            ZeroSettleError.BackendError(statusCode = 500, body = "err"),
            ZeroSettleError.NetworkError(RuntimeException("dns")),
            ZeroSettleError.PlayApiUnreachable,
            ZeroSettleError.ProductNotFound("pro"),
            ZeroSettleError.NotBootstrapped,
            ZeroSettleError.NoActiveSubscription("pro"),
            ZeroSettleError.AlreadyMigrated("pro"),
            ZeroSettleError.OfferIneligible,
            ZeroSettleError.MerchantNotOnboarded,
            ZeroSettleError.JurisdictionBlocked("UK"),
        )
        cases.forEach { e ->
            val handled: String = when (e) {
                is ZeroSettleError.NotConfigured -> "nc"
                is ZeroSettleError.UserNotIdentified -> "uni"
                is ZeroSettleError.UserIdRequired -> "uir"
                is ZeroSettleError.InvalidUserId -> "iui"
                is ZeroSettleError.CheckoutFailed -> "cf"
                is ZeroSettleError.PurchaseCancelled -> "pc"
                is ZeroSettleError.PurchasePending -> "pp"
                is ZeroSettleError.PlayBillingError -> "pbe"
                is ZeroSettleError.BackendError -> "be"
                is ZeroSettleError.NetworkError -> "ne"
                is ZeroSettleError.PlayApiUnreachable -> "pau"
                is ZeroSettleError.ProductNotFound -> "pnf"
                is ZeroSettleError.NotBootstrapped -> "nb"
                is ZeroSettleError.NoActiveSubscription -> "nas"
                is ZeroSettleError.AlreadyMigrated -> "am"
                is ZeroSettleError.OfferIneligible -> "oi"
                is ZeroSettleError.MerchantNotOnboarded -> "mo"
                is ZeroSettleError.JurisdictionBlocked -> "jb"
            }
            assertThat(handled).isNotEmpty()
        }
    }

    @Test fun messages_arePopulated() {
        assertThat(ZeroSettleError.NotConfigured.message).contains("configure")
        assertThat(ZeroSettleError.InvalidUserId("blank").message).contains("blank")
        assertThat(ZeroSettleError.BackendError(500, "x").message).contains("500")
        assertThat(ZeroSettleError.NetworkError(RuntimeException("dns")).message).contains("dns")
        assertThat(ZeroSettleError.JurisdictionBlocked("UK").message).contains("UK")
    }
}
