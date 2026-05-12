package com.zerosettle.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Process-lifetime coroutine scope for SDK background work (sync queue draining,
 * polling, reconcile). Cancelled by [com.zerosettle.sdk.ZeroSettle.logout] /
 * [com.zerosettle.sdk.ZeroSettle.resetForTesting] via the parent job replacement.
 *
 * Mirrors iOS `Task { @MainActor in ... }` patterns + `AsyncStream`-fed observers.
 */
internal class ZeroSettleScope {
    private var current: CoroutineScope = newScope()

    val scope: CoroutineScope get() = current

    fun reset() {
        current.cancel()
        current = newScope()
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
