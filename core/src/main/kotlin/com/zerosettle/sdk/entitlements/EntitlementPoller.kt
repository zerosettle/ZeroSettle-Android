package com.zerosettle.sdk.entitlements

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.zerosettle.sdk.core.Backend
import com.zerosettle.sdk.models.Entitlement
import com.zerosettle.sdk.models.PendingAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls `GET /v1/iap/entitlements/` and publishes the result + the decoded
 * `pending_actions[]` (chunk 3 surface). Cadence:
 *  - on app foreground (`ProcessLifecycleOwner` `ON_START`)
 *  - after a purchase / migration completes ([pollNow] — called by the `ZeroSettle`
 *    facade's Play / offer glue)
 *  - every [FOREGROUND_POLL_INTERVAL_MILLIS] (5 min) while foregrounded
 *  - paused when backgrounded (the periodic loop is cancelled on `ON_STOP`)
 *
 * Pure constructor injection; [start]/[stop] manage the `ProcessLifecycleOwner`
 * hookup. `internal` — this is SDK wiring, not public API; consumers observe results
 * via `ZeroSettle.entitlements` / `ZeroSettle.pendingActions`.
 */
internal class EntitlementPoller(
    private val backend: Backend,
    private val userIdProvider: () -> String?,
    private val onEntitlements: (List<Entitlement>) -> Unit,
    private val onPendingActions: (List<PendingAction>) -> Unit,
    private val onUnknownActionType: (String) -> Unit,
) {
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var periodicJob: Job? = null

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            scope?.launch { pollOnce() }
            startPeriodic()
        }
        override fun onStop(owner: LifecycleOwner) {
            periodicJob?.cancel(); periodicJob = null
        }
    }

    /** Hook into the process lifecycle and run periodic polling on [scope]. Idempotent-ish — call [stop] first to re-bind. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun stop() {
        runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver) }
        periodicJob?.cancel(); periodicJob = null
        scope = null
    }

    /** Trigger an immediate poll (after a purchase / migration). No-op if [start] hasn't run. */
    fun pollNow() { scope?.launch { pollOnce() } }

    private fun startPeriodic() {
        periodicJob?.cancel()
        periodicJob = scope?.launch {
            while (isActive) {
                delay(FOREGROUND_POLL_INTERVAL_MILLIS)
                pollOnce()
            }
        }
    }

    /** One poll cycle. Visible for testing. Silently no-ops without an identified user or on backend failure. */
    suspend fun pollOnce() {
        val uid = userIdProvider() ?: return
        val resp = backend.fetchEntitlements(uid).getOrNull() ?: return
        onEntitlements(resp.entitlements)
        onPendingActions(PendingActionParser.parse(resp.pendingActions, onUnknownActionType))
    }

    companion object {
        const val FOREGROUND_POLL_INTERVAL_MILLIS: Long = 5L * 60 * 1000
    }
}
