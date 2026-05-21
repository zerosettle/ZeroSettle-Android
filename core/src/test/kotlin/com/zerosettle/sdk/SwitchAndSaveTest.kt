package com.zerosettle.sdk

import android.app.Activity
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [launchSwitchAndSaveOrchestrated].
 *
 * All collaborators (isAvailable, newTransactionToken, mintSession, launch, endConnection) are
 * simple lambda fakes — no mocking framework, no BillingClient, no MockWebServer. Tests drive
 * the internal orchestrator directly; the public [ZeroSettle.launchSwitchAndSave] shim is
 * covered by the wiring (it delegates straight through).
 */
@RunWith(RobolectricTestRunner::class)
class SwitchAndSaveTest {

    private val activity: Activity = org.robolectric.Robolectric.buildActivity(Activity::class.java).get()

    // ─── Unavailable path ─────────────────────────────────────────────────────

    @Test fun `unavailable - returns SwitchAndSaveUnavailable without calling launch`() = runTest {
        var launchCalled = false
        var endConnectionCalled = false

        val result = launchSwitchAndSaveOrchestrated(
            activity = activity,
            isAvailable = { false },
            newTransactionToken = { error("should not be called") },
            mintSession = { error("should not be called") },
            launch = { _, _ -> launchCalled = true; Result.success(Unit) },
            endConnection = { endConnectionCalled = true },
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(ZeroSettleError.SwitchAndSaveUnavailable::class.java)
        assertThat(launchCalled).isFalse()
        // endConnection must still fire even on the unavailable early-return path
        assertThat(endConnectionCalled).isTrue()
    }

    // ─── newTransactionToken failure ──────────────────────────────────────────

    @Test fun `token failure - propagates failure without calling mintSession or launch`() = runTest {
        val tokenError = ZeroSettleError.PlayBillingError(responseCode = 3, debugMessage = "service_unavailable")
        var mintSessionCalled = false
        var launchCalled = false
        var endConnectionCalled = false

        val result = launchSwitchAndSaveOrchestrated(
            activity = activity,
            isAvailable = { true },
            newTransactionToken = { Result.failure(tokenError) },
            mintSession = { mintSessionCalled = true; error("should not be called") },
            launch = { _, _ -> launchCalled = true; error("should not be called") },
            endConnection = { endConnectionCalled = true },
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isSameInstanceAs(tokenError)
        assertThat(mintSessionCalled).isFalse()
        assertThat(launchCalled).isFalse()
        assertThat(endConnectionCalled).isTrue()
    }

    // ─── mintSession failure ──────────────────────────────────────────────────

    @Test fun `mintSession failure - propagates failure without calling launch`() = runTest {
        val backendError = ZeroSettleError.BackendError(statusCode = 503, body = "unavailable")
        var launchCalled = false
        var endConnectionCalled = false

        val result = launchSwitchAndSaveOrchestrated(
            activity = activity,
            isAvailable = { true },
            newTransactionToken = { Result.success("fake-ecl-token") },
            mintSession = { Result.failure(backendError) },
            launch = { _, _ -> launchCalled = true; error("should not be called") },
            endConnection = { endConnectionCalled = true },
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isSameInstanceAs(backendError)
        assertThat(launchCalled).isFalse()
        assertThat(endConnectionCalled).isTrue()
    }

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Test fun `happy path - token forwarded to mintSession, launch called with migrationUri`() = runTest {
        val fakeToken = "ecl-token-abc123"
        val fakeMigrationUrl = "https://checkout.zerosettle.io/switch?session=opaque42"
        var capturedToken: String? = null
        var capturedUri: Uri? = null
        var endConnectionCalled = false

        val result = launchSwitchAndSaveOrchestrated(
            activity = activity,
            isAvailable = { true },
            newTransactionToken = { Result.success(fakeToken) },
            mintSession = { token ->
                capturedToken = token
                Result.success(fakeMigrationUrl)
            },
            launch = { _, uri ->
                capturedUri = uri
                Result.success(Unit)
            },
            endConnection = { endConnectionCalled = true },
        )

        assertThat(result.isSuccess).isTrue()
        // The ECL token must be forwarded verbatim to mintSession
        assertThat(capturedToken).isEqualTo(fakeToken)
        // launch must receive a Uri parsed from the backend's migration URL
        assertThat(capturedUri).isEqualTo(Uri.parse(fakeMigrationUrl))
        assertThat(endConnectionCalled).isTrue()
    }

    @Test fun `happy path - launch failure is propagated`() = runTest {
        val launchError = ZeroSettleError.CheckoutFailed("ecl_disclosure_rejected")
        var endConnectionCalled = false

        val result = launchSwitchAndSaveOrchestrated(
            activity = activity,
            isAvailable = { true },
            newTransactionToken = { Result.success("token") },
            mintSession = { Result.success("https://checkout.zerosettle.io/switch?session=x") },
            launch = { _, _ -> Result.failure(launchError) },
            endConnection = { endConnectionCalled = true },
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isSameInstanceAs(launchError)
        assertThat(endConnectionCalled).isTrue()
    }
}
