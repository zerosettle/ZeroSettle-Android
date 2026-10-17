package com.zerosettle.sdk.entitlements

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.zerosettle.sdk.access.AccessStore
import com.zerosettle.sdk.core.Backend
import com.zerosettle.sdk.models.Access
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
    // The store the host holds when a poll starts; handed back with the result so a reconfigure
    // mid-request cannot land one environment's access in another's slot.
    private val accessStoreProvider: () -> AccessStore? = { null },
    private val onAccess: (userId: String, access: Access, store: AccessStore?) -> Unit = { _, _, _ -> },
) {
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var periodicJob: Job? = null
    // The lifecycle's first ON_START after we register fires synchronously if the
    // process is already foregrounded — which it is when [start] runs from bootstrap,
    // and bootstrap just fetched entitlements. Skip the immediate poll on that first
    // edge; subsequent ON_STARTs (real background→foreground returns) do poll.
    @Volatile private var sawFirstStart = false

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (sawFirstStart) scope?.launch { pollOnce() } else sawFirstStart = true
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

    /** True between [start] and [stop]. Visible for testing. */
    val isStarted: Boolean get() = scope != null

    fun stop() {
        runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver) }
        periodicJob?.cancel(); periodicJob = null
        scope = null
        sawFirstStart = false
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
        val store = accessStoreProvider()
        val resp = backend.fetchEntitlements(uid).getOrNull() ?: return
        onEntitlements(resp.entitlements)
        onAccess(uid, resp.access(), store)
        onPendingActions(PendingActionParser.parse(resp.pendingActions, onUnknownActionType))
    }

    companion object {
        const val FOREGROUND_POLL_INTERVAL_MILLIS: Long = 5L * 60 * 1000
    }
}
