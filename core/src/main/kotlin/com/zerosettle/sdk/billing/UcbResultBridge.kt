package com.zerosettle.sdk.billing

import kotlinx.coroutines.CompletableDeferred

/**
 * Outcome of the Stripe `PaymentSheet` presentation that
 * [StripeCheckoutLauncher] hosts inside [UcbPaymentSheetActivity].
 *
 * Distinct from [com.zerosettle.sdk.models.ZeroSettleError] so the bridge
 * stays a thin Activity↔launcher channel — the launcher maps each variant
 * onto an SDK-facing `Result` at its boundary.
 */
internal sealed class UcbPurchaseOutcome {
    object Completed : UcbPurchaseOutcome()
    object Canceled : UcbPurchaseOutcome()
    data class Failed(val message: String) : UcbPurchaseOutcome()
}

/**
 * Process-static bridge that carries [UcbPurchaseOutcome] from
 * [UcbPaymentSheetActivity] back to the suspending caller in
 * [StripeCheckoutLauncher].
 *
 * **Why a static bridge, not `ActivityResultLauncher` / `startActivityForResult`?**
 *
 * The SDK's checkout launcher is constructed with a [android.content.Context]
 * (the host Application), NOT an Activity. The Jetpack `ActivityResult` APIs
 * require an Activity / Fragment so they can register the contract in
 * `onCreate` before `STARTED`. We deliberately don't take an Activity here
 * — adopters would have to thread one through every purchase call.
 *
 * `startActivityForResult` is deprecated in API 30+ and doesn't compose with
 * a `suspend fun launch()` call site without callback gymnastics.
 *
 * So we use a process-static rendezvous: the launcher reserves the bridge
 * (a fresh [CompletableDeferred]) before dispatching the Intent, then awaits.
 * The Activity completes the deferred at its result delivery point. The
 * pattern is closely related to how `ZeroSettleWebViewActivity` routes its
 * deep-link return through [com.zerosettle.sdk.ZeroSettle.completeWebCheckout]
 * (which fans out to a SDK-singleton-owned deferred), and is functionally
 * equivalent — just scoped to UCB instead of web checkout.
 *
 * **Single-flight invariant.** Only one UCB checkout can be in flight at a
 * time (Google issues exactly one `externalTransactionToken` per
 * `launchBillingFlow`; a second concurrent call would have nothing to
 * present). [reserve] surfaces a failure if a bridge is already armed —
 * callers should defensively `reset()` on application shutdown.
 *
 * **Threading.** [deliver] is safe to call from any thread (the activity's
 * main thread); [await] resumes on whatever dispatcher the caller's
 * coroutine is on.
 */
internal object UcbResultBridge {

    @Volatile
    private var pending: CompletableDeferred<UcbPurchaseOutcome>? = null

    /**
     * Reserve the bridge for one outcome delivery. Returns the deferred to
     * await. If a previous bridge is still armed, the previous deferred is
     * completed exceptionally with a cancellation so the abandoned caller
     * doesn't leak — fail-loud rather than silent overwrite.
     *
     * **No parent Job.** The returned [CompletableDeferred] is created with
     * `parent = null` so it doesn't attach itself to whatever
     * `CoroutineScope` happens to call [reserve]. This matters in two cases:
     *
     *   1. In tests using `kotlinx.coroutines.test.runTest`, an attached
     *      [CompletableDeferred] would register a child Job on the test
     *      scope's job. Even after the test body returns a `Result`, the
     *      uncompleted child throws [kotlinx.coroutines.test.UncompletedCoroutinesError].
     *   2. In production, the bridge lifecycle is independent of any
     *      particular call site (the Activity completes it via the
     *      process-static [deliver]); attaching it to the caller's scope
     *      would inherit cancellation semantics we don't want.
     */
    @Synchronized
    fun reserve(): CompletableDeferred<UcbPurchaseOutcome> {
        pending?.let { stale ->
            // A previous launch never received its result. Most likely the
            // activity crashed or was force-killed. Complete the stale
            // deferred so the abandoned coroutine resumes (with Failed),
            // then arm a fresh one.
            stale.complete(UcbPurchaseOutcome.Failed("abandoned: a new UCB checkout was started before the previous one completed"))
        }
        val fresh = CompletableDeferred<UcbPurchaseOutcome>(parent = null)
        pending = fresh
        return fresh
    }

    /**
     * Deliver an outcome to whoever is currently awaiting the bridge. No-op
     * if no one is awaiting — silently absorbs a duplicate-delivery from a
     * recreated activity (e.g., rotation racing PaymentSheet result).
     */
    @Synchronized
    fun deliver(outcome: UcbPurchaseOutcome) {
        val d = pending ?: return
        d.complete(outcome)
        pending = null
    }

    /**
     * Clear the bridge unconditionally. Used by tests + on SDK shutdown +
     * by the launcher on `/initiate/` failure paths (the bridge was reserved
     * before the failure but no Activity will ever deliver into it). Any
     * pending deferred is completed with [UcbPurchaseOutcome.Failed] so a
     * concurrent awaiter doesn't deadlock.
     */
    @Synchronized
    fun reset() {
        pending?.complete(UcbPurchaseOutcome.Failed("bridge reset before activity delivered an outcome"))
        pending = null
    }

    /** Test-only: snapshot the current pending state — DO NOT use in production code. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun peekForTest(): UcbPurchaseOutcome? {
        val d = pending ?: return null
        if (!d.isCompleted) return null
        return runCatching { d.getCompleted() }.getOrNull()
    }

    /** Test-only: returns true iff a deferred is currently reserved (not yet delivered or reset). */
    internal fun isReservedForTest(): Boolean = pending != null
}
