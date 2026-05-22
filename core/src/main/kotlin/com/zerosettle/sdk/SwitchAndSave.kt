package com.zerosettle.sdk

import android.app.Activity
import android.net.Uri
import com.zerosettle.sdk.billing.ExternalContentLinkClient
import com.zerosettle.sdk.checkout.WebCheckoutFlow
import com.zerosettle.sdk.models.ZeroSettleError
import java.util.UUID

/**
 * Orchestrates the External Content Link (ECL) Switch & Save flow:
 *
 *  1. Guard: `isAvailable()` — ECL billing program must be reachable on this device/account.
 *  2. `newTransactionToken()` — Play Billing issues a single-use attribution token.
 *  3. `mintSession(token)` — backend mints an opaque migration-session URL embedding the token.
 *  4. `launch(activity, uri)` — Play shows the ECL disclosure dialog; on acknowledgement,
 *     opens the migration URL in a Chrome Custom Tab.
 *
 * `endConnection()` is called in a `finally` block, ensuring the Play Billing service binding
 * is released on EVERY exit path — success, failure, and cancellation.
 *
 * All parameters are suspend-function references rather than the concrete
 * [ExternalContentLinkClient] type so this function is unit-testable without a mocking
 * framework. The public [ZeroSettle.launchSwitchAndSave] shim binds these from the
 * real [ExternalContentLinkClient] and [com.zerosettle.sdk.core.Backend].
 *
 * @param activity The foreground [Activity] used to anchor the ECL disclosure dialog.
 * @param isAvailable Returns `true` if ECL is available on this device/account.
 * @param newTransactionToken Issues a fresh Play Billing ECL attribution token.
 * @param mintSession Backend call: exchanges [token] for the migration-session URL.
 * @param launch Opens the disclosure dialog and, on approval, launches the URL.
 * @param endConnection Releases the Play Billing service binding (always called).
 */
internal suspend fun launchSwitchAndSaveOrchestrated(
    activity: Activity,
    isAvailable: suspend () -> Boolean,
    newTransactionToken: suspend () -> Result<String>,
    mintSession: suspend (token: String) -> Result<String>,
    launch: suspend (Activity, Uri) -> Result<Unit>,
    endConnection: () -> Unit,
): Result<Unit> {
    try {
        if (!isAvailable()) {
            return Result.failure(ZeroSettleError.SwitchAndSaveUnavailable)
        }
        val token = newTransactionToken().getOrElse { return Result.failure(it) }
        val url = mintSession(token).getOrElse { return Result.failure(it) }
        return launch(activity, Uri.parse(url))
    } finally {
        endConnection()
    }
}

/**
 * High-level Switch & Save entry point for the ECL billing program.
 *
 * Orchestrates the full flow:
 *  1. Checks ECL availability via Play Billing — returns
 *     `Result.failure(ZeroSettleError.SwitchAndSaveUnavailable)` if unavailable.
 *  2. Issues a single-use ECL attribution token from Play Billing.
 *  3. Exchanges the token for a migration-session URL from the ZeroSettle backend
 *     (`POST /v1/iap/switch-and-save/session/`).
 *  4. Opens the ECL disclosure dialog and, on the user's acknowledgement, launches
 *     the migration URL in a Chrome Custom Tab via [ExternalContentLinkClient].
 *
 * The Play Billing service binding is held open across the sequence and released in a
 * `finally` block — guaranteed on success, failure, and coroutine cancellation.
 *
 * When [ZeroSettle.switchAndSaveTestMode] is `true`, the Play ECL plumbing (availability,
 * token, disclosure dialog) is faked so the flow runs on non-ECL devices — the backend
 * session mint and the Chrome Custom Tab web checkout still run for real. The orchestrator
 * contract is unchanged; only the bound collaborators differ.
 *
 * Requires [identify] to have been called (`ZeroSettleError.UserNotIdentified` otherwise).
 * Requires [configure] (`ZeroSettleError.NotConfigured` otherwise).
 *
 * @param activity The foreground [Activity] used to anchor the ECL disclosure dialog.
 */
public suspend fun ZeroSettle.launchSwitchAndSave(activity: Activity): Result<Unit> {
    val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
    val be = backend ?: return Result.failure(ZeroSettleError.NotConfigured)
    val ctx = appContext ?: return Result.failure(ZeroSettleError.NotConfigured)

    val testMode = switchAndSaveTestMode
    if (testMode) {
        config?.logger?.warn(
            "SwitchAndSave",
            "Switch & Save TEST MODE active via ZeroSettle.switchAndSaveTestMode — " +
                "Play ECL plumbing (availability, token, disclosure dialog) is faked; " +
                "backend session mint and web checkout are real. " +
                "Testing only — must be false in production builds.",
        )
    }
    val collaborators = selectSwitchAndSaveCollaborators(
        testMode = testMode,
        eclClientFactory = { ExternalContentLinkClient(ctx) },
    )
    return launchSwitchAndSaveOrchestrated(
        activity = activity,
        isAvailable = collaborators.isAvailable,
        newTransactionToken = collaborators.newTransactionToken,
        mintSession = { token -> be.mintSwitchAndSaveSession(userId = uid, externalTransactionToken = token) },
        launch = collaborators.launch,
        endConnection = collaborators.endConnection,
    )
}

/**
 * The four [launchSwitchAndSaveOrchestrated] collaborators that differ between the
 * production ECL path and Switch & Save test-mode. `mintSession` is excluded — it is
 * the real backend call in both modes.
 */
internal class SwitchAndSaveCollaborators(
    val isAvailable: suspend () -> Boolean,
    val newTransactionToken: suspend () -> Result<String>,
    val launch: suspend (Activity, Uri) -> Result<Unit>,
    val endConnection: () -> Unit,
)

/**
 * Picks the Switch & Save collaborators for the current mode.
 *
 * Production ([testMode] `false`): builds an [ExternalContentLinkClient] via
 * [eclClientFactory] and binds the real Play-ECL plumbing — availability query,
 * attribution-token request, disclosure dialog, connection teardown.
 *
 * Test-mode ([testMode] `true`): fakes the Play-ECL plumbing so the flow runs on
 * devices/accounts not enrolled in Google's ECL program — availability is forced
 * `true`, a synthetic `zs_test_`-prefixed attribution token is used, the ECL disclosure
 * dialog is skipped (the Chrome Custom Tab opens directly via [launchCustomTab]), and
 * connection teardown is a no-op. [eclClientFactory] is NOT called — no
 * [ExternalContentLinkClient] is constructed and no Play Billing service is bound.
 *
 * Split out from [ZeroSettle.launchSwitchAndSave] so the mode selection is unit-testable
 * without a configured [ZeroSettle] or a real Play Billing binding; [launchCustomTab] is
 * injectable for the same reason.
 */
internal fun selectSwitchAndSaveCollaborators(
    testMode: Boolean,
    eclClientFactory: () -> ExternalContentLinkClient,
    launchCustomTab: (Activity, String) -> Unit = WebCheckoutFlow::launchCustomTab,
): SwitchAndSaveCollaborators =
    if (testMode) {
        SwitchAndSaveCollaborators(
            isAvailable = { true },
            newTransactionToken = { Result.success("zs_test_" + UUID.randomUUID()) },
            launch = { act, uri -> launchCustomTab(act, uri.toString()); Result.success(Unit) },
            endConnection = { /* no ExternalContentLinkClient was constructed */ },
        )
    } else {
        val eclClient = eclClientFactory()
        SwitchAndSaveCollaborators(
            isAvailable = { eclClient.isAvailable() },
            newTransactionToken = { eclClient.newTransactionToken() },
            launch = { act, uri -> eclClient.launch(act, uri) },
            endConnection = { eclClient.endConnection() },
        )
    }
