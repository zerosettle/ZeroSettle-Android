package com.zerosettle.sdk.billing

import kotlinx.coroutines.CompletableDeferred

/**
 * Outcome of the Stripe `PaymentSheet` presentation that
 * [StripeCheckoutLauncher] hosts inside [UcbPaymentSheetActivity].
 *
 * Distinct from [com.zerosettle.sdk.models.ZeroSettleError] so the bridge
 * stays a thin Activity↔launcher channel — the launcher maps each variant
 * onto an SDK-facing `Result` at its boundary.
 *
 * [Completed] carries the IDs minted by the `/v1/iap/play-ucb/initiate/`
 * response so the deferred-bridge consumer in [com.zerosettle.sdk.ZeroSettle]
 * can hydrate a `CheckoutTransaction` without a second round-trip. The
 * activity doesn't know these IDs — they're folded in by the bridge from
 * what the launcher reserved.
 */
internal sealed class UcbPurchaseOutcome {
    /**
     * @property externalTransactionId The opaque ID minted by the backend's
     *   `/v1/iap/play-ucb/initiate/` (matches `Transaction.external_transaction_id`).
     * @property transactionRef The canonical `ucb_*` string id of the
     *   transaction (matches `transaction_ref` in the `/initiate/` response).
     *   `GET /v1/iap/transactions/{id}/` resolves on this — the integer
     *   `Transaction.id` PK 404s there. Null when an older backend didn't
     *   emit `transaction_ref`; the deferred-bridge consumer then builds a
     *   local `CheckoutTransaction` instead of fetching.
     */
    data class Completed(
        val externalTransactionId: String,
        val transactionRef: String?,
    ) : UcbPurchaseOutcome()
    object Canceled : UcbPurchaseOutcome()
    data class Failed(val message: String) : UcbPurchaseOutcome()
}

/**
 * Result signal that [UcbPaymentSheetActivity] knows how to emit — the
 * activity ONLY observes whether the PaymentSheet completed/canceled/failed;
 * the transaction IDs were reserved by the launcher from the `/initiate/`
 * response and aren't visible from inside the PaymentSheet callback.
 *
 * The bridge composes this with the reserved IDs to produce the final
 * [UcbPurchaseOutcome] handed to the suspending caller.
 */
internal sealed class PaymentSheetStatus {
    object Completed : PaymentSheetStatus()
    object Canceled : PaymentSheetStatus()
    data class Failed(val message: String) : PaymentSheetStatus()
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
 * (a fresh [CompletableDeferred] + the IDs from `/initiate/`) before
 * dispatching the Intent, then awaits. The Activity completes the deferred
 * at its result delivery point — emitting a [PaymentSheetStatus] which the
 * bridge composes into a final [UcbPurchaseOutcome] using the reserved IDs.
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

    /**
     * Holds the deferred + the IDs the launcher reserved from the `/initiate/`
     * response. The IDs are baked into the composed [UcbPurchaseOutcome] by
     * [deliver] when the activity delivers a successful
     * [PaymentSheetStatus.Completed].
     */
    private data class Pending(
        val deferred: CompletableDeferred<UcbPurchaseOutcome>,
        val externalTransactionId: String,
        val transactionRef: String?,
    )

    @Volatile
    private var pending: Pending? = null

    /**
     * Reserve the bridge for one outcome delivery with the IDs minted by the
     * backend's `/initiate/` response. Returns the deferred to await. If a
     * previous bridge is still armed, the previous deferred is completed
     * with a `Failed` outcome so the abandoned caller doesn't leak —
     * fail-loud rather than silent overwrite.
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
     *
     * @param externalTransactionId The `external_transaction_id` from the
     *   `/initiate/` response — baked into [UcbPurchaseOutcome.Completed]
     *   on a successful PaymentSheet result so the deferred-bridge consumer
     *   in [com.zerosettle.sdk.ZeroSettle] can hydrate a `CheckoutTransaction`
     *   without a second round-trip.
     * @param transactionRef Canonical `ucb_*` transaction id from the
     *   `/initiate/` response (or null when an older backend didn't emit it).
     */
    @Synchronized
    fun reserve(
        externalTransactionId: String,
        transactionRef: String?,
    ): CompletableDeferred<UcbPurchaseOutcome> {
        pending?.let { stale ->
            // A previous launch never received its result. Most likely the
            // activity crashed or was force-killed. Complete the stale
            // deferred so the abandoned coroutine resumes (with Failed),
            // then arm a fresh one.
            stale.deferred.complete(
                UcbPurchaseOutcome.Failed(
                    "abandoned: a new UCB checkout was started before the previous one completed",
                ),
            )
        }
        val fresh = CompletableDeferred<UcbPurchaseOutcome>(parent = null)
        pending = Pending(
            deferred = fresh,
            externalTransactionId = externalTransactionId,
            transactionRef = transactionRef,
        )
        return fresh
    }

    /**
     * Deliver a PaymentSheet result. The bridge composes the final
     * [UcbPurchaseOutcome] using the IDs reserved by the launcher: a
     * `Completed` status becomes `UcbPurchaseOutcome.Completed(extId, txnRef)`,
     * other statuses pass through. No-op if no one is awaiting — silently
     * absorbs a duplicate-delivery from a recreated activity (e.g., rotation
     * racing PaymentSheet result).
     */
    @Synchronized
    fun deliver(status: PaymentSheetStatus) {
        val p = pending ?: return
        val outcome = when (status) {
            is PaymentSheetStatus.Completed -> UcbPurchaseOutcome.Completed(
                externalTransactionId = p.externalTransactionId,
                transactionRef = p.transactionRef,
            )
            is PaymentSheetStatus.Canceled -> UcbPurchaseOutcome.Canceled
            is PaymentSheetStatus.Failed -> UcbPurchaseOutcome.Failed(status.message)
        }
        p.deferred.complete(outcome)
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
        pending?.deferred?.complete(
            UcbPurchaseOutcome.Failed("bridge reset before activity delivered an outcome"),
        )
        pending = null
    }

    /** Test-only: snapshot the current pending state — DO NOT use in production code. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun peekForTest(): UcbPurchaseOutcome? {
        val d = pending?.deferred ?: return null
        if (!d.isCompleted) return null
        return runCatching { d.getCompleted() }.getOrNull()
    }

    /** Test-only: returns true iff a deferred is currently reserved (not yet delivered or reset). */
    internal fun isReservedForTest(): Boolean = pending != null
}
