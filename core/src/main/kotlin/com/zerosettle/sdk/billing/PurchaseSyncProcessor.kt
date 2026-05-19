package com.zerosettle.sdk.billing

import com.zerosettle.sdk.core.Backend
import com.zerosettle.sdk.core.ZeroSettleEvent
import com.zerosettle.sdk.core.ZeroSettleLogger
import com.zerosettle.sdk.models.PendingClaim
import com.zerosettle.sdk.models.ZeroSettleError

/**
 * Drives a Play purchase through: sync to `POST /v1/iap/play-store-transactions/` →
 * (on `owned=true`) finalize via the Billing library → (on backend failure)
 * enqueue to [PlaySyncQueue], leaving the purchase un-finalized so Play redelivers.
 *
 * Finalization ordering matters: Apple's `transaction.finish()` has no consequence
 * if skipped, but Play **auto-refunds** an unacknowledged purchase after 3 days. So:
 *
 *  - Backend confirms (`owned=true`) → finalize immediately.
 *  - Backend fails (5xx / timeout) → DO NOT finalize; enqueue; Play redelivers.
 *  - In [retryQueued], if a queued purchase has been failing for >24h and
 *    [strictAck] is false (default), the SDK finalizes **defensively** — the user
 *    did pay Google, so the purchase is almost certainly valid; the SDK keeps trying
 *    to sync. If [strictAck] is true, the SDK never finalizes without backend validation
 *    (Play auto-refunds; user gets their money back, no entitlement).
 *  - `owned=false` / `conflict=true` → DO NOT finalize; surface a [PendingClaim].
 *
 * The [finalize] callback is product-type-aware: consumables go through
 * `BillingClient.consumeAsync` (acknowledges AND releases the SKU so the user can
 * buy it again — leaving a consumable merely acknowledged traps the user in
 * `ITEM_ALREADY_OWNED` on the next purchase attempt), while non-consumables and
 * subscriptions go through `BillingClient.acknowledgePurchase`. The routing
 * decision lives in [PlayBillingCoordinator] which constructs this processor.
 *
 * Pure constructor injection (no Android types) for testability. [finalize] is
 * wired to a closure over `PlayBillingManager::acknowledge` / `::consume`;
 * [onConflictClaim] publishes to `ZeroSettle.pendingClaims`.
 */
internal class PurchaseSyncProcessor(
    private val backend: Backend,
    private val queue: PlaySyncQueue,
    private val finalize: suspend (productId: String, purchaseToken: String) -> Result<Unit>,
    private val emitEvent: (ZeroSettleEvent) -> Unit,
    private val onConflictClaim: (PendingClaim) -> Unit = {},
    // ─── Deferred-bridge callbacks (Task A3) ─────────────────────────────────
    //
    // Fired ONLY from the listener-driven [process] call — never from
    // [retryQueued]. A retry drains rows from prior sessions whose original
    // awaiter is gone; resolving the *current* awaiter's deferred with one of
    // those transactionIds would deliver the wrong purchase to the caller.
    //
    // Ordering contract: [onPurchaseSynced] fires BEFORE the
    // [ZeroSettleEvent.PurchaseSucceeded] event emit so direct
    // `purchaseViaPlayBilling()` callers resume before events-stream observers
    // see the event — consistent observable sequence.
    //
    // Both default to no-ops so existing callers (and tests) keep working.
    private val onPurchaseSynced: (transactionId: String) -> Unit = {},
    private val onPurchaseFailed: (error: ZeroSettleError) -> Unit = {},
    private val strictAck: Boolean = false,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    // Used to surface a non-OK [finalize] result. [finalize] returns a
    // `Result<Unit>` carrying the consume/acknowledge BillingResult outcome; a
    // returned `Result.failure` (non-OK BillingResult) is NOT a thrown
    // exception, so the surrounding `runCatching` would silently swallow it.
    // Nullable + null-default so existing call sites compile unchanged.
    private val logger: ZeroSettleLogger? = null,
) {

    private companion object {
        const val DEFENSIVE_ACK_WINDOW_MILLIS = 24L * 60 * 60 * 1000
    }

    /**
     * Run [finalize] and make BOTH failure modes observable:
     *  - a thrown exception (caught by the outer `runCatching` so a Billing
     *    disconnect doesn't crash the sync loop), and
     *  - a returned `Result.failure` (a non-OK consume/acknowledge
     *    BillingResult) — previously discarded entirely.
     * Logging only; the queue/redelivery behavior is intentionally unchanged.
     */
    private suspend fun finalizeAndLog(productId: String, purchaseToken: String) {
        runCatching { finalize(productId, purchaseToken) }
            .onSuccess { result ->
                result.onFailure { e ->
                    logger?.warn("billing", "finalize failed for $productId: ${e.message}", e)
                }
            }
            .onFailure { e ->
                logger?.warn("billing", "finalize threw for $productId: ${e.message}", e)
            }
    }

    /** Sync one freshly-observed purchase. */
    public suspend fun process(d: PurchaseDescriptor): Result<Unit> {
        if (d.purchaseState == 2 /* PENDING */) {
            // Resolve the awaiter so a parental-approval / pending purchase
            // doesn't hang `purchaseViaPlayBilling()` forever waiting on a
            // listener fire that won't come until Google decides.
            onPurchaseFailed(ZeroSettleError.PurchasePending)
            return Result.failure(ZeroSettleError.PurchasePending)
        }

        val syncRes = backend.syncPlayPurchase(
            userId = d.userId, purchaseToken = d.purchaseToken, productId = d.productId,
            packageName = d.packageName, orderId = d.orderId, purchaseState = d.purchaseState,
            isAcknowledged = d.isAcknowledged, signature = d.signature, originalJson = d.originalJson,
            willAutoRenew = d.willAutoRenew, customerName = d.customerName, customerEmail = d.customerEmail,
        )

        val resp = syncRes.getOrElse { err ->
            queue.enqueue(
                PendingPurchaseSync(
                    purchaseToken = d.purchaseToken, productId = d.productId, packageName = d.packageName,
                    userId = d.userId, orderId = d.orderId, signature = d.signature, originalJson = d.originalJson,
                ),
            )
            queue.recordFailure(d.purchaseToken, nowMillis())
            val wrapped = err as? ZeroSettleError ?: ZeroSettleError.NetworkError(err)
            onPurchaseFailed(wrapped)
            return Result.failure(err)
        }

        return when {
            resp.owned -> {
                queue.remove(d.purchaseToken)
                val txnId = resp.transactionId ?: ""
                // Resolve the awaiter FIRST — as soon as OUR backend has recorded
                // the purchase the user-visible operation is done. The local Play
                // ack that follows is bookkeeping that satisfies the 3-day window
                // rule; it must not gate the awaiter resolution because
                // `acknowledge` going through `BillingClient.ensureConnected` can
                // throw (no Play services in Robolectric, transient disconnect on
                // device). A throw between the awaiter-resolve point and a now-
                // moved-down ack would have stranded the deferred. Resolving
                // first means even an `acknowledge` throw is harmless to the
                // suspending caller; we still wrap it in `runCatching` so the
                // throw doesn't surface out of `process()` and stop the
                // coordinator's purchase loop (it's logged via the silent catch).
                if (txnId.isNotEmpty()) {
                    onPurchaseSynced(txnId)
                } else {
                    onPurchaseFailed(ZeroSettleError.CheckoutFailed("missing_transaction_id"))
                }
                emitEvent(ZeroSettleEvent.PurchaseSucceeded(productId = d.productId, transactionId = txnId))
                if (!d.isAcknowledged) finalizeAndLog(d.productId, d.purchaseToken)
                Result.success(Unit)
            }
            resp.conflict && resp.claimAvailable -> {
                onConflictClaim(
                    PendingClaim(
                        productId = d.productId,
                        originalTransactionId = resp.transactionId ?: d.orderId.orEmpty(),
                        existingOwnerHint = resp.existingOwnerHint ?: "another account",
                    ),
                )
                onPurchaseFailed(ZeroSettleError.CheckoutFailed("ownership_conflict"))
                Result.failure(ZeroSettleError.CheckoutFailed("ownership_conflict"))
            }
            else -> {
                emitEvent(ZeroSettleEvent.PurchaseFailed(productId = d.productId, reason = "not_owned"))
                onPurchaseFailed(ZeroSettleError.CheckoutFailed("not_owned"))
                Result.failure(ZeroSettleError.CheckoutFailed("not_owned"))
            }
        }
    }

    /** Drain the queue: retry each row that's past its backoff window; defensive-ack stale ones. */
    public suspend fun retryQueued() {
        for (row in queue.pending()) {
            if (PlaySyncQueue.isAbandoned(row)) {
                if (!strictAck && row.lastAttemptAtMillis != null &&
                    nowMillis() - row.lastAttemptAtMillis >= DEFENSIVE_ACK_WINDOW_MILLIS
                ) {
                    finalizeAndLog(row.productId, row.purchaseToken) // defensive finalize; keep the row so we keep trying to sync
                }
                emitEvent(ZeroSettleEvent.SyncFailed(purchaseToken = row.purchaseToken, attempts = row.attemptCount, terminal = true))
                continue
            }
            val delay = PlaySyncQueue.backoffDelayMillis(row.attemptCount) ?: continue
            if (row.lastAttemptAtMillis != null && nowMillis() - row.lastAttemptAtMillis < delay) continue

            val syncRes = backend.syncPlayPurchase(
                userId = row.userId, purchaseToken = row.purchaseToken, productId = row.productId,
                packageName = row.packageName, orderId = row.orderId, purchaseState = 1,
                isAcknowledged = false, signature = row.signature.orEmpty(), originalJson = row.originalJson.orEmpty(),
                willAutoRenew = true, customerName = null, customerEmail = null,
            )
            syncRes.fold(
                onSuccess = { resp ->
                    if (resp.owned) {
                        finalizeAndLog(row.productId, row.purchaseToken)
                        queue.remove(row.purchaseToken)
                        emitEvent(ZeroSettleEvent.PurchaseSucceeded(productId = row.productId, transactionId = resp.transactionId ?: ""))
                    } else {
                        queue.recordFailure(row.purchaseToken, nowMillis())
                    }
                },
                onFailure = {
                    queue.recordFailure(row.purchaseToken, nowMillis())
                    emitEvent(ZeroSettleEvent.SyncFailed(purchaseToken = row.purchaseToken, attempts = row.attemptCount + 1, terminal = false))
                },
            )
        }
    }
}
