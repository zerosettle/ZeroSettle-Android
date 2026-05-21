package com.zerosettle.sdk

import android.app.Activity
import android.net.Uri
import com.zerosettle.sdk.billing.ExternalContentLinkClient
import com.zerosettle.sdk.models.ZeroSettleError

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
 * Requires [identify] to have been called (`ZeroSettleError.UserNotIdentified` otherwise).
 * Requires [configure] (`ZeroSettleError.NotConfigured` otherwise).
 *
 * @param activity The foreground [Activity] used to anchor the ECL disclosure dialog.
 */
public suspend fun ZeroSettle.launchSwitchAndSave(activity: Activity): Result<Unit> {
    val uid = currentUserIdOrNull() ?: return Result.failure(ZeroSettleError.UserNotIdentified)
    val be = backend ?: return Result.failure(ZeroSettleError.NotConfigured)
    val ctx = appContext ?: return Result.failure(ZeroSettleError.NotConfigured)

    val eclClient = ExternalContentLinkClient(ctx)
    return launchSwitchAndSaveOrchestrated(
        activity = activity,
        isAvailable = { eclClient.isAvailable() },
        newTransactionToken = { eclClient.newTransactionToken() },
        mintSession = { token -> be.mintSwitchAndSaveSession(userId = uid, externalTransactionToken = token) },
        launch = { act, uri -> eclClient.launch(act, uri) },
        endConnection = { eclClient.endConnection() },
    )
}
