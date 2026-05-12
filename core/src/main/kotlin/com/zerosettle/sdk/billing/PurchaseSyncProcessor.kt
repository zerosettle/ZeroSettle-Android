package com.zerosettle.sdk.billing

import com.zerosettle.sdk.core.Backend
import com.zerosettle.sdk.core.ZeroSettleEvent
import com.zerosettle.sdk.models.PendingClaim
import com.zerosettle.sdk.models.ZeroSettleError

/**
 * Drives a Play purchase through: sync to `POST /v1/iap/play-store-transactions/` →
 * (on `owned=true`) acknowledge via the Billing library → (on backend failure)
 * enqueue to [PlaySyncQueue], leaving the purchase unacknowledged so Play redelivers.
 *
 * Acknowledgement ordering matters: Apple's `transaction.finish()` has no consequence
 * if skipped, but Play **auto-refunds** an unacknowledged purchase after 3 days. So:
 *
 *  - Backend confirms (`owned=true`) → acknowledge immediately.
 *  - Backend fails (5xx / timeout) → DO NOT acknowledge; enqueue; Play redelivers.
 *  - In [retryQueued], if a queued purchase has been failing for >24h and
 *    [strictAck] is false (default), the SDK acknowledges **defensively** — the user
 *    did pay Google, so the purchase is almost certainly valid; the SDK keeps trying
 *    to sync. If [strictAck] is true, the SDK never acks without backend validation
 *    (Play auto-refunds; user gets their money back, no entitlement).
 *  - `owned=false` / `conflict=true` → DO NOT acknowledge; surface a [PendingClaim].
 *
 * Pure constructor injection (no Android types) for testability. [acknowledge] is
 * `PlayBillingManager::acknowledge`; [onConflictClaim] publishes to `ZeroSettle.pendingClaims`.
 */
internal class PurchaseSyncProcessor(
    private val backend: Backend,
    private val queue: PlaySyncQueue,
    private val acknowledge: suspend (purchaseToken: String) -> Result<Unit>,
    private val emitEvent: (ZeroSettleEvent) -> Unit,
    private val onConflictClaim: (PendingClaim) -> Unit = {},
    private val strictAck: Boolean = false,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private companion object {
        const val DEFENSIVE_ACK_WINDOW_MILLIS = 24L * 60 * 60 * 1000
    }

    /** Sync one freshly-observed purchase. */
    public suspend fun process(d: PurchaseDescriptor): Result<Unit> {
        if (d.purchaseState == 2 /* PENDING */) return Result.failure(ZeroSettleError.PurchasePending)

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
            return Result.failure(err)
        }

        return when {
            resp.owned -> {
                if (!d.isAcknowledged) acknowledge(d.purchaseToken)
                queue.remove(d.purchaseToken)
                emitEvent(ZeroSettleEvent.PurchaseSucceeded(productId = d.productId, transactionId = resp.transactionId ?: ""))
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
                Result.failure(ZeroSettleError.CheckoutFailed("ownership_conflict"))
            }
            else -> {
                emitEvent(ZeroSettleEvent.PurchaseFailed(productId = d.productId, reason = "not_owned"))
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
                    acknowledge(row.purchaseToken) // defensive ack; keep the row so we keep trying to sync
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
                        acknowledge(row.purchaseToken)
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
