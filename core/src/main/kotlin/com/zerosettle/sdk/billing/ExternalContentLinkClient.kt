package com.zerosettle.sdk.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingProgramAvailabilityListener
import com.android.billingclient.api.BillingResult
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Eligibility check for Google's External Content Link (ECL) billing program.
 *
 * ECL lets qualifying apps offer a web-checkout ("Switch & Save") flow to
 * existing Play Store subscribers. Before presenting the offer, the SDK must
 * confirm that the device + account is eligible via
 * [BillingClient.isBillingProgramAvailableAsync] with
 * [BillingClient.BillingProgram.EXTERNAL_CONTENT_LINK].
 *
 * This is a **reporting-only** client: no [com.android.billingclient.api.PurchasesUpdatedListener]
 * is registered. The client builds with `enableBillingProgram(EXTERNAL_CONTENT_LINK)`, which
 * is the ECL-specific enablement method in PBL 8.2.1.
 *
 * ## Connection lifecycle
 *
 * The client holds its billing connection **open across an entire ECL session** —
 * the Switch & Save flow runs `isAvailable()` → (token) → `launch()` on a single
 * instance, and all three share one connection (reuse is gated on
 * [BillingClient.isReady]). The connection is therefore NOT auto-released after
 * `isAvailable()`.
 *
 * **The caller MUST call [endConnection] when the Switch & Save flow finishes** —
 * on success, failure, or abandonment. Without it, the Play Services service
 * binding leaks for the process lifetime. (Task 5's `launchSwitchAndSave` wires
 * this teardown.)
 *
 * ## Coroutine bridging
 *
 * Mirrors [PlayBillingManager.ensureConnected]: each `isAvailable()` call
 * establishes a billing connection via [BillingClient.startConnection] inside a
 * [suspendCancellableCoroutine] and then immediately calls
 * [BillingClient.isBillingProgramAvailableAsync] — also bridged via
 * [suspendCancellableCoroutine]. Both async callbacks are fired on the Play
 * Services thread and resume the coroutine from that thread; the caller must
 * dispatch to the appropriate context if main-thread access is needed.
 *
 * ## Usage
 *
 * ```kotlin
 * val eclClient = ExternalContentLinkClient(context)
 * try {
 *     val eligible = eclClient.isAvailable()
 *     // ... newTransactionToken(), launch() ...
 * } finally {
 *     eclClient.endConnection()
 * }
 * ```
 *
 * @param client Pre-built [BillingClient] — primary constructor for injection
 *   (tests, custom configurations). Teardown is via [endConnection].
 */
public class ExternalContentLinkClient(private val client: BillingClient) {

    /**
     * Secondary convenience constructor: builds the dedicated ECL [BillingClient]
     * from [context] using `enableBillingProgram(EXTERNAL_CONTENT_LINK)`.
     *
     * No [com.android.billingclient.api.PurchasesUpdatedListener] is set — ECL
     * availability checks are read-only and emit no purchase callbacks.
     */
    public constructor(context: Context) : this(
        BillingClient.newBuilder(context.applicationContext)
            .enableBillingProgram(BillingClient.BillingProgram.EXTERNAL_CONTENT_LINK)
            .build(),
    )

    /**
     * Returns `true` if the ECL billing program is available on this device
     * for the current user account, `false` otherwise.
     *
     * Internally: connects the billing client, then calls
     * [BillingClient.isBillingProgramAvailableAsync]. A [BillingResult] with
     * [BillingClient.BillingResponseCode.OK] maps to `true`; any other code
     * (e.g. `FEATURE_NOT_SUPPORTED`, `BILLING_UNAVAILABLE`, `SERVICE_UNAVAILABLE`)
     * maps to `false`.
     *
     * Connection failures are also treated as `false` — the ECL offer is simply
     * suppressed rather than propagating a billing error to the caller.
     */
    public suspend fun isAvailable(): Boolean {
        val connectOk = connect()
        if (!connectOk) return false
        return checkAvailability()
    }

    /**
     * Releases the billing connection. Mirrors [PlayBillingManager.endConnection].
     *
     * Must be called once the Switch & Save ECL session is finished (success,
     * failure, or abandonment) — the client holds the connection open across
     * `isAvailable()` → token → `launch()`, so it cannot self-close. Idempotent:
     * safe to call on an already-disconnected client.
     */
    public fun endConnection() { client.endConnection() }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Establishes (or re-establishes) the billing connection. Returns `true` on
     * success ([BillingResponseCode.OK]), `false` on any error.
     *
     * If the client is already [BillingClient.isReady], returns `true` immediately
     * without re-connecting — mirrors [PlayBillingManager.ensureConnected].
     */
    private suspend fun connect(): Boolean = suspendCancellableCoroutine { cont ->
        if (client.isReady) { cont.resume(true); return@suspendCancellableCoroutine }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
            override fun onBillingServiceDisconnected() { /* next call re-connects */ }
        })
    }

    /**
     * Calls [BillingClient.isBillingProgramAvailableAsync] with
     * [BillingClient.BillingProgram.EXTERNAL_CONTENT_LINK] and suspends until
     * the listener fires. Returns `true` iff [BillingResult.responseCode] is
     * [BillingClient.BillingResponseCode.OK].
     */
    private suspend fun checkAvailability(): Boolean = suspendCancellableCoroutine { cont ->
        client.isBillingProgramAvailableAsync(
            BillingClient.BillingProgram.EXTERNAL_CONTENT_LINK,
            BillingProgramAvailabilityListener { result, _ ->
                cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            },
        )
    }
}
