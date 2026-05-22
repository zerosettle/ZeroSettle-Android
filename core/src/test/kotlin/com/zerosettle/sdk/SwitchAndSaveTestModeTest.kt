package com.zerosettle.sdk

import android.app.Activity
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.billing.ExternalContentLinkClient
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the Switch & Save test-mode wiring — [selectSwitchAndSaveCollaborators].
 *
 * Test strategy: the `launchSwitchAndSave` shim resolves global [ZeroSettle] state
 * (backend, appContext, identity), so the mode selection was extracted into the pure
 * [selectSwitchAndSaveCollaborators] helper (mirroring how [launchSwitchAndSaveOrchestrated]
 * is a lambda-based test seam). These tests drive that helper directly — no configured
 * [ZeroSettle], no real Play Billing binding, no `ZeroSettle.switchAndSaveTestMode`
 * global is touched, so there is no cross-test state to reset.
 *
 * The Phase 3 implication (test-mode ⇒ ECL-available for offer eligibility) is covered
 * separately in [ZeroSettleOffersTest].
 */
@RunWith(RobolectricTestRunner::class)
class SwitchAndSaveTestModeTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

    // ─── Test-mode (flag on) ──────────────────────────────────────────────────

    @Test fun `test-mode - isAvailable resolves true without constructing an ECL client`() = runTest {
        val collaborators = selectSwitchAndSaveCollaborators(
            testMode = true,
            eclClientFactory = { error("ExternalContentLinkClient must not be constructed in test-mode") },
        )
        assertThat(collaborators.isAvailable()).isTrue()
    }

    @Test fun `test-mode - newTransactionToken yields a synthetic zs_test_ prefixed token`() = runTest {
        val collaborators = selectSwitchAndSaveCollaborators(
            testMode = true,
            eclClientFactory = { error("ExternalContentLinkClient must not be constructed in test-mode") },
        )
        val token = collaborators.newTransactionToken().getOrThrow()
        assertThat(token).startsWith("zs_test_")
    }

    @Test fun `test-mode - launch invokes the custom-tab launcher and returns success`() = runTest {
        val customTabCalls = mutableListOf<Pair<Activity, String>>()
        val collaborators = selectSwitchAndSaveCollaborators(
            testMode = true,
            eclClientFactory = { error("ExternalContentLinkClient must not be constructed in test-mode") },
            launchCustomTab = { act, url -> customTabCalls.add(act to url) },
        )
        val uri = Uri.parse("https://checkout.zerosettle.io/switch?session=opaque42")

        val result = collaborators.launch(activity, uri)

        assertThat(result.isSuccess).isTrue()
        assertThat(customTabCalls).hasSize(1)
        assertThat(customTabCalls[0].first).isSameInstanceAs(activity)
        assertThat(customTabCalls[0].second).isEqualTo(uri.toString())
    }

    @Test fun `test-mode - endConnection is a no-op`() {
        val collaborators = selectSwitchAndSaveCollaborators(
            testMode = true,
            eclClientFactory = { error("ExternalContentLinkClient must not be constructed in test-mode") },
        )
        // No ExternalContentLinkClient was constructed — endConnection must not throw.
        collaborators.endConnection()
    }

    @Test fun `test-mode - orchestrated flow forwards the synthetic token to the real mintSession`() = runTest {
        var capturedToken: String? = null
        var customTabUrl: String? = null
        val collaborators = selectSwitchAndSaveCollaborators(
            testMode = true,
            eclClientFactory = { error("ExternalContentLinkClient must not be constructed in test-mode") },
            launchCustomTab = { _, url -> customTabUrl = url },
        )

        val result = launchSwitchAndSaveOrchestrated(
            activity = activity,
            isAvailable = collaborators.isAvailable,
            newTransactionToken = collaborators.newTransactionToken,
            // The real mintSession is unchanged in test-mode; here a fake stands in
            // for the backend call and captures the token the synthetic plumbing produced.
            mintSession = { token ->
                capturedToken = token
                Result.success("https://checkout.zerosettle.io/switch?session=minted")
            },
            launch = collaborators.launch,
            endConnection = collaborators.endConnection,
        )

        assertThat(result.isSuccess).isTrue()
        // The synthetic zs_test_ token reaches the backend mint verbatim.
        assertThat(capturedToken).isNotNull()
        assertThat(capturedToken).startsWith("zs_test_")
        // The minted URL is what the Custom Tab opens — the real web-checkout half.
        assertThat(customTabUrl).isEqualTo("https://checkout.zerosettle.io/switch?session=minted")
    }

    // ─── Production (flag off) ────────────────────────────────────────────────

    @Test fun `flag off - selects the production path and constructs the ECL client`() {
        var factoryCalls = 0
        selectSwitchAndSaveCollaborators(
            testMode = false,
            eclClientFactory = {
                factoryCalls++
                ExternalContentLinkClient(activity.applicationContext)
            },
        )
        // The production branch builds the real ExternalContentLinkClient exactly once;
        // the test-mode branch never calls the factory.
        assertThat(factoryCalls).isEqualTo(1)
    }
}
