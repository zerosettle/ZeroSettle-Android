package com.zerosettle.sdk.offers

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.UserOffer
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class OfferManagerTest {

    private fun migrationDisplay() = UserOffer.OfferDisplay(
        title = "Save 20%", body = "Switch", ctaText = "Switch now",
        dismissText = "Not now", acceptedTitle = "All set", acceptedBody = "Done",
        completedTitle = "Switched", completedBody = "Welcome",
    )

    private fun migrationOffer(presentation: UserOffer.CheckoutPresentation = UserOffer.CheckoutPresentation.WEBVIEW) =
        UserOffer.OfferData(
            actionType = UserOffer.ActionType.MIGRATE_STOREKIT_TO_WEB,
            isEligible = true,
            checkoutProductId = "pro_monthly",
            fromProductId = "pro_monthly",
            savingsPercent = 20,
            freeTrialDays = 7,
            minSubscriptionDays = 14,
            rolloutPercent = 100,
            display = migrationDisplay(),
            requiresAppleCancel = true,
            checkoutPresentation = presentation,
            source = UserOffer.SourceStorefront.PLAY_STORE,
        )

    private fun webToWebUpgrade() = UserOffer.OfferData(
        actionType = UserOffer.ActionType.UPGRADE_WEB_TO_WEB,
        isEligible = true,
        checkoutProductId = "pro_yearly",
        fromProductId = "pro_monthly",
        savingsPercent = 20,
        rolloutPercent = 100,
        display = migrationDisplay(),
        requiresAppleCancel = false,
        checkoutPresentation = UserOffer.CheckoutPresentation.WEBVIEW,
        source = null,
    )

    private fun response(offer: UserOffer.OfferData, subType: String = "active_storekit") =
        UserOffer.Response(
            userId = "u1", appId = 1, isSandbox = true,
            subscription = UserOffer.Subscription(type = subType, productId = "pro_monthly"),
            offer = offer,
            serverTime = "2026-05-12T00:00:00Z",
        )

    private fun ineligibleResponse() = UserOffer.Response(
        userId = "u1", appId = 1, isSandbox = true,
        subscription = UserOffer.Subscription(type = "none"),
        offer = UserOffer.OfferData(
            actionType = UserOffer.ActionType.NO_ACTION,
            isEligible = false,
            checkoutProductId = "",
        ),
        serverTime = "2026-05-12T00:00:00Z",
    )

    private fun makeManager(
        offerResult: Result<UserOffer.Response> = Result.success(response(migrationOffer())),
        dismissed: Boolean = false,
        createCheckout: suspend (productId: String, playToken: String?) -> Result<String> = { _, _ -> Result.success("https://c/x") },
        playTokenProvider: () -> String? = { "ptok_active" },
        trackConversion: suspend (source: String) -> Result<Unit> = { Result.success(Unit) },
        autoRenewOff: () -> Boolean = { false },
        executeUpgrade: suspend (from: String, to: String) -> Result<Unit> = { _, _ -> Result.success(Unit) },
        onEvent: (OfferManager.OfferEvent) -> Unit = {},
        isEclAvailable: suspend () -> Boolean = { true },
        launchSwitchAndSave: suspend (Activity) -> Result<Unit> = { Result.success(Unit) },
    ) = OfferManager(
        fetchUserOffer = { offerResult },
        isDismissed = { dismissed },
        persistDismissal = { },
        createWebCheckout = createCheckout,
        activePlayPurchaseTokenProvider = playTokenProvider,
        trackMigrationConversion = trackConversion,
        playSubAutoRenewOff = autoRenewOff,
        launchCheckout = { _ -> },
        onEvent = onEvent,
        executeUpgradeOffer = executeUpgrade,
        isEclAvailable = isEclAvailable,
        launchSwitchAndSave = launchSwitchAndSave,
    )

    @Test fun evaluate_eligible_movesToPresented() = runTest {
        val m = makeManager()
        m.evaluate()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.PRESENTED)
        assertThat(m.offerData.first()?.actionType).isEqualTo(UserOffer.ActionType.MIGRATE_STOREKIT_TO_WEB)
    }

    @Test fun evaluate_dismissed_movesToIneligible() = runTest {
        val m = makeManager(dismissed = true)
        m.evaluate()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.INELIGIBLE)
    }

    @Test fun evaluate_notEligible_movesToIneligible() = runTest {
        val m = makeManager(offerResult = Result.success(ineligibleResponse()))
        m.evaluate()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.INELIGIBLE)
    }

    @Test fun evaluate_fetchFails_movesToErrorAndEmitsEvaluationFailed() = runTest {
        val events = mutableListOf<OfferManager.OfferEvent>()
        val m = makeManager(
            offerResult = Result.failure(ZeroSettleError.NetworkError(IOException("boom"))),
            onEvent = { events += it },
        )
        m.evaluate()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.ERROR)
        assertThat(m.checkoutError.first()).isNotNull()
        assertThat(events.filterIsInstance<OfferManager.OfferEvent.EvaluationFailed>()).isNotEmpty()
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
            offerResult = Result.success(response(webToWebUpgrade(), subType = "active_web")),
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

    @Test fun acceptOffer_migration_publishesPendingCheckoutUrl_clearedOnSuccess() = runTest {
        val m = makeManager(createCheckout = { _, _ -> Result.success("https://checkout/x") })
        m.evaluate()
        assertThat(m.pendingCheckoutUrl.first()).isNull()
        m.acceptOffer()
        assertThat(m.pendingCheckoutUrl.first()).isEqualTo("https://checkout/x")
        m.onWebCheckoutSucceeded()
        assertThat(m.pendingCheckoutUrl.first()).isNull()
    }

    @Test fun webToWebUpgrade_acceptOffer_doesNotPublishPendingCheckoutUrl() = runTest {
        val m = makeManager(offerResult = Result.success(response(webToWebUpgrade(), subType = "active_web")))
        m.evaluate(); m.acceptOffer()
        assertThat(m.pendingCheckoutUrl.first()).isNull()
    }

    @Test fun cancelPendingCheckout_clearsUrl_keepsAcceptedState() = runTest {
        val m = makeManager()
        m.evaluate(); m.acceptOffer()
        assertThat(m.pendingCheckoutUrl.first()).isNotNull()
        m.cancelPendingCheckout()
        assertThat(m.pendingCheckoutUrl.first()).isNull()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.ACCEPTED)
    }

    @Test fun dismiss_movesToDismissedAndPersists() = runTest {
        var persisted = false
        val m = OfferManager(
            fetchUserOffer = { Result.success(response(migrationOffer())) },
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

    // ─── MIGRATE_PLAY_TO_WEB tests ────────────────────────────────────────────

    private fun playToWebOffer() = UserOffer.OfferData(
        actionType = UserOffer.ActionType.MIGRATE_PLAY_TO_WEB,
        isEligible = true,
        checkoutProductId = "pro_monthly_web",
        fromProductId = "pro_monthly",
        savingsPercent = 15,
        rolloutPercent = 100,
        display = migrationDisplay(),
        source = UserOffer.SourceStorefront.PLAY_STORE,
    )

    /** Decode `"migrate_play_to_web"` wire string to [UserOffer.ActionType.MIGRATE_PLAY_TO_WEB]. */
    @Test fun migratePlayToWeb_actionType_decodesFromWireString() {
        val decoded = kotlinx.serialization.json.Json.decodeFromString(
            UserOffer.ActionType.serializer(),
            "\"migrate_play_to_web\"",
        )
        assertThat(decoded).isEqualTo(UserOffer.ActionType.MIGRATE_PLAY_TO_WEB)
    }

    /** Round-trip: [UserOffer.ActionType.MIGRATE_PLAY_TO_WEB] encodes to exact wire string. */
    @Test fun migratePlayToWeb_actionType_encodesToWireString() {
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            UserOffer.ActionType.serializer(),
            UserOffer.ActionType.MIGRATE_PLAY_TO_WEB,
        )
        assertThat(encoded).isEqualTo("\"migrate_play_to_web\"")
    }

    // acceptOffer(activity) tests for MIGRATE_PLAY_TO_WEB live in
    // OfferManagerPlayToWebTest (Robolectric — needs a real Activity instance).

    /**
     * The no-arg [OfferManager.acceptOffer] must REJECT a MIGRATE_PLAY_TO_WEB offer —
     * a Play→web migration must never fall back to the in-app WebView checkout (a
     * Google Play policy violation). It returns [ZeroSettleError.SwitchAndSaveRequiresActivity]
     * and does NOT call createWebCheckout or publish a pending checkout URL.
     */
    @Test fun migratePlayToWeb_noArgAcceptOffer_rejected_doesNotCallWebCheckout() = runTest {
        var webCheckoutCalled = false
        val m = makeManager(
            offerResult = Result.success(response(playToWebOffer(), subType = "active_storekit")),
            isEclAvailable = { true },
            createCheckout = { _, _ -> webCheckoutCalled = true; Result.success("https://c/x") },
        )
        m.evaluate()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.PRESENTED)

        val res = m.acceptOffer()
        assertThat(res.isFailure).isTrue()
        assertThat(res.exceptionOrNull()).isInstanceOf(ZeroSettleError.SwitchAndSaveRequiresActivity::class.java)
        assertThat(webCheckoutCalled).isFalse()
        assertThat(m.pendingCheckoutUrl.first()).isNull()
    }

    /**
     * [OfferManager.checkoutUrl] (the raw-URL escape hatch) must also REJECT a
     * MIGRATE_PLAY_TO_WEB offer — it must never mint a Stripe checkout URL for a
     * Play→web migration.
     */
    @Test fun migratePlayToWeb_checkoutUrl_rejected_doesNotCallWebCheckout() = runTest {
        var webCheckoutCalled = false
        val m = makeManager(
            offerResult = Result.success(response(playToWebOffer(), subType = "active_storekit")),
            isEclAvailable = { true },
            createCheckout = { _, _ -> webCheckoutCalled = true; Result.success("https://c/x") },
        )
        m.evaluate()

        val res = m.checkoutUrl()
        assertThat(res.isFailure).isTrue()
        assertThat(res.exceptionOrNull()).isInstanceOf(ZeroSettleError.SwitchAndSaveRequiresActivity::class.java)
        assertThat(webCheckoutCalled).isFalse()
    }

    /**
     * ECL NOT available + MIGRATE_PLAY_TO_WEB offer → evaluate moves to INELIGIBLE
     * (the offer is suppressed; Shown event is NOT emitted).
     */
    @Test fun migratePlayToWeb_eclUnavailable_evaluateMovesToIneligible() = runTest {
        val events = mutableListOf<OfferManager.OfferEvent>()
        val m = makeManager(
            offerResult = Result.success(response(playToWebOffer(), subType = "active_storekit")),
            isEclAvailable = { false },
            onEvent = { events += it },
        )
        m.evaluate()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.INELIGIBLE)
        assertThat(events.filterIsInstance<OfferManager.OfferEvent.Shown>()).isEmpty()
    }

    /**
     * ECL available + MIGRATE_PLAY_TO_WEB offer → evaluate moves to PRESENTED.
     */
    @Test fun migratePlayToWeb_eclAvailable_evaluateMovesToPresented() = runTest {
        val m = makeManager(
            offerResult = Result.success(response(playToWebOffer(), subType = "active_storekit")),
            isEclAvailable = { true },
        )
        m.evaluate()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.PRESENTED)
    }

    /**
     * ECL is unavailable but the offer is a MIGRATE_STOREKIT_TO_WEB (not Play→web):
     * the offer must still surface (PRESENTED). The ECL gate is action-type-specific.
     */
    @Test fun storeKitMigration_eclUnavailable_stillPresented() = runTest {
        val m = makeManager(
            offerResult = Result.success(response(migrationOffer())),
            isEclAvailable = { false },
        )
        m.evaluate()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.PRESENTED)
    }

    // acceptOffer(activity) failure test lives in OfferManagerPlayToWebTest (Robolectric).
}
