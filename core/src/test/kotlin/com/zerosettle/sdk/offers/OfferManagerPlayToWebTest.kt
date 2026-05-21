package com.zerosettle.sdk.offers

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.UserOffer
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [OfferManager] routing Play→web migration offers ([UserOffer.ActionType.MIGRATE_PLAY_TO_WEB])
 * to [ZeroSettle.launchSwitchAndSave] via [OfferManager.acceptOffer(Activity)].
 *
 * Separated from [OfferManagerTest] because these tests require a real [Activity] instance
 * from Robolectric — the existing suite is pure-JVM (no Robolectric runner) and we
 * avoid adding that dependency to the whole class.
 */
@RunWith(RobolectricTestRunner::class)
class OfferManagerPlayToWebTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

    private fun migrationDisplay() = UserOffer.OfferDisplay(
        title = "Save 15%", body = "Switch", ctaText = "Switch now",
        dismissText = "Not now", acceptedTitle = "All set", acceptedBody = "Done",
        completedTitle = "Switched", completedBody = "Welcome",
    )

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

    private fun response(offer: UserOffer.OfferData, subType: String = "active_storekit") =
        UserOffer.Response(
            userId = "u1", appId = 1, isSandbox = true,
            subscription = UserOffer.Subscription(type = subType, productId = "pro_monthly"),
            offer = offer,
            serverTime = "2026-05-20T00:00:00Z",
        )

    private fun makeManager(
        offerResult: Result<UserOffer.Response> = Result.success(response(playToWebOffer())),
        isEclAvailable: suspend () -> Boolean = { true },
        launchSwitchAndSave: suspend (Activity) -> Result<Unit> = { Result.success(Unit) },
        createCheckout: suspend (String, String?) -> Result<String> = { _, _ -> Result.success("https://c/x") },
        onEvent: (OfferManager.OfferEvent) -> Unit = {},
    ) = OfferManager(
        fetchUserOffer = { offerResult },
        isDismissed = { false },
        persistDismissal = { },
        createWebCheckout = createCheckout,
        activePlayPurchaseTokenProvider = { null },
        trackMigrationConversion = { Result.success(Unit) },
        playSubAutoRenewOff = { false },
        launchCheckout = { },
        onEvent = onEvent,
        isEclAvailable = isEclAvailable,
        launchSwitchAndSave = launchSwitchAndSave,
    )

    /**
     * ECL available + MIGRATE_PLAY_TO_WEB: acceptOffer(activity) routes to launchSwitchAndSave
     * (NOT createWebCheckout), and state → ACCEPTED.
     */
    @Test fun acceptOffer_withActivity_routesToSwitchAndSave_notWebCheckout() = runTest {
        var switchAndSaveCalled = false
        var webCheckoutCalled = false
        val m = makeManager(
            isEclAvailable = { true },
            launchSwitchAndSave = { _ -> switchAndSaveCalled = true; Result.success(Unit) },
            createCheckout = { _, _ -> webCheckoutCalled = true; Result.success("https://c/x") },
        )
        m.evaluate()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.PRESENTED)

        val res = m.acceptOffer(activity)
        assertThat(res.isSuccess).isTrue()
        assertThat(switchAndSaveCalled).isTrue()
        assertThat(webCheckoutCalled).isFalse()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.ACCEPTED)
    }

    /**
     * When launchSwitchAndSave fails, state stays PRESENTED and checkoutError is set.
     */
    @Test fun acceptOffer_switchAndSaveFails_staysPresented_setsError() = runTest {
        val m = makeManager(
            isEclAvailable = { true },
            launchSwitchAndSave = { _ -> Result.failure(ZeroSettleError.SwitchAndSaveUnavailable) },
        )
        m.evaluate()
        val res = m.acceptOffer(activity)
        assertThat(res.isFailure).isTrue()
        assertThat(m.state.first()).isEqualTo(OfferManager.OfferState.PRESENTED)
        assertThat(m.checkoutError.first()).isInstanceOf(ZeroSettleError.SwitchAndSaveUnavailable::class.java)
    }
}
