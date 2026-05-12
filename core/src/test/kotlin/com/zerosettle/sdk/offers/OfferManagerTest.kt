package com.zerosettle.sdk.offers

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.Offer
import com.zerosettle.sdk.models.UserOffer
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OfferManagerTest {

    private fun migrationOffer(presentation: Offer.CheckoutPresentation = Offer.CheckoutPresentation.CUSTOM_TAB) =
        Offer.OfferData(
            flowType = Offer.FlowType.MIGRATION, upgradeType = null,
            sourceStorefront = Offer.SourceStorefront.PLAY_STORE,
            productId = "pro_monthly", eligibleProductIds = listOf("pro_monthly"),
            savingsPercent = 20,
            display = Offer.OfferDisplay("Save 20%", "Switch", "Switch now", "All set", "Done", "Continue", "Switched", "Welcome"),
            freeTrialDays = 7, minSubscriptionDays = 14, maxSubscriptionDays = null,
            rolloutPercent = 100, checkoutPresentation = presentation,
        )

    private fun webToWebUpgrade() = migrationOffer().copy(
        flowType = Offer.FlowType.UPGRADE, upgradeType = Offer.UpgradeType.WEB_TO_WEB,
        fromProductId = "pro_monthly", toProductId = "pro_yearly", productId = "pro_yearly",
    )

    private fun makeManager(
        offerResponse: UserOffer.Response = UserOffer.Response(isEligible = true, source = Offer.SourceStorefront.PLAY_STORE, offer = migrationOffer()),
        dismissed: Boolean = false,
        createCheckout: suspend (productId: String, playToken: String?) -> Result<String> = { _, _ -> Result.success("https://c/x") },
        playTokenProvider: () -> String? = { "ptok_active" },
        trackConversion: suspend (source: String) -> Result<Unit> = { Result.success(Unit) },
        autoRenewOff: () -> Boolean = { false },
        executeUpgrade: suspend (from: String, to: String) -> Result<Unit> = { _, _ -> Result.success(Unit) },
        onEvent: (OfferManager.OfferEvent) -> Unit = {},
    ) = OfferManager(
        fetchUserOffer = { Result.success(offerResponse) },
        isDismissed = { dismissed },
        persistDismissal = { },
        createWebCheckout = createCheckout,
        activePlayPurchaseTokenProvider = playTokenProvider,
        trackMigrationConversion = trackConversion,
        playSubAutoRenewOff = autoRenewOff,
        launchCheckout = { _ -> },
        onEvent = onEvent,
        executeUpgradeOffer = executeUpgrade,
    )

    @Test fun evaluate_eligible_movesToPresented() = runTest {
        val m = makeManager()
        m.evaluate()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.PRESENTED)
        assertThat(m.offerData.first()?.flowType).isEqualTo(Offer.FlowType.MIGRATION)
    }

    @Test fun evaluate_dismissed_movesToIneligible() = runTest {
        val m = makeManager(dismissed = true)
        m.evaluate()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.INELIGIBLE)
    }

    @Test fun evaluate_notEligible_movesToIneligible() = runTest {
        val m = makeManager(offerResponse = UserOffer.Response(isEligible = false))
        m.evaluate()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.INELIGIBLE)
    }

    @Test fun acceptOffer_migration_createsCheckoutWithPlayToken_thenAccepted() = runTest {
        var tokenSeen: String? = null
        val m = makeManager(createCheckout = { _, token -> tokenSeen = token; Result.success("https://c/x") })
        m.evaluate()
        val res = m.acceptOffer()
        assertThat(res.isSuccess).isTrue()
        assertThat(tokenSeen).isEqualTo("ptok_active")
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.ACCEPTED)
    }

    @Test fun acceptOffer_serviceUnavailable_setsCheckoutErrorAndStaysPresented() = runTest {
        val m = makeManager(createCheckout = { _, _ -> Result.failure(ZeroSettleError.PlayApiUnreachable) })
        m.evaluate()
        val res = m.acceptOffer()
        assertThat(res.isFailure).isTrue()
        assertThat(m.checkoutError.first()).isInstanceOf(ZeroSettleError.PlayApiUnreachable::class.java)
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.PRESENTED)
    }

    @Test fun webToWebUpgrade_acceptOffer_jumpsStraightToCompleted() = runTest {
        var executed: Pair<String, String>? = null
        val m = makeManager(
            offerResponse = UserOffer.Response(isEligible = true, offer = webToWebUpgrade()),
            executeUpgrade = { from, to -> executed = from to to; Result.success(Unit) },
        )
        m.evaluate()
        m.acceptOffer()
        assertThat(executed).isEqualTo("pro_monthly" to "pro_yearly")
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.COMPLETED)
    }

    @Test fun completeWebCheckout_migration_recordsConversionWithPlaySource_thenWaitsForAutoRenewOff() = runTest {
        var convSource: String? = null
        val renewOff = booleanArrayOf(false)
        val m = makeManager(trackConversion = { convSource = it; Result.success(Unit) }, autoRenewOff = { renewOff[0] })
        m.evaluate(); m.acceptOffer()
        m.onWebCheckoutSucceeded()
        assertThat(convSource).isEqualTo("play_store")
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.ACCEPTED)  // not COMPLETED until store cancels
        renewOff[0] = true
        m.observeStoreCancellation()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.COMPLETED)
    }

    @Test fun dismiss_movesToDismissedAndPersists() = runTest {
        var persisted = false
        val m = OfferManager(
            fetchUserOffer = { Result.success(UserOffer.Response(isEligible = true, offer = migrationOffer())) },
            isDismissed = { false }, persistDismissal = { persisted = true },
            createWebCheckout = { _, _ -> Result.success("x") }, activePlayPurchaseTokenProvider = { "ptok" },
            trackMigrationConversion = { Result.success(Unit) }, playSubAutoRenewOff = { false },
            launchCheckout = { }, onEvent = { },
        )
        m.evaluate()
        m.dismiss()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.DISMISSED)
        assertThat(persisted).isTrue()
    }
}
