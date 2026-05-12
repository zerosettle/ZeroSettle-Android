package com.zerosettle.sdk.billing

import com.zerosettle.sdk.core.Backend

/** A Play purchase as the reconciler sees it (decoupled from `com.android.billingclient.api.Purchase`). */
public data class ReconcilePurchase(
    val purchaseToken: String,
    val productId: String,
    val packageName: String,
    val originalJson: String,
    val signature: String,
)

/** Summary returned by [SubscriptionReconciler.reconcile]. */
public data class ReconcileOutcome(
    val processed: Int,
    val eventsEmitted: Int,
)

/**
 * Bulk-reconciles the device's current Play purchases against the backend on launch
 * and after each `PurchasesUpdatedListener` event. Android analog of iOS
 * `SubscriptionStateReconciler`. Posts a `transactions: [...]` array to
 * `POST /v1/iap/play-store-transactions/` (the chunk-1 processor dispatches array
 * payloads to its bulk handler). Does not retry on failure — the next launch /
 * purchase event re-runs.
 *
 * Internal: takes the [Backend] wrapper (itself internal) and maps the internal
 * `BulkReconcileTransaction` DTO. Host apps trigger reconciliation indirectly via
 * `ZeroSettle.purchaseViaPlayBilling()` / launch.
 */
internal class SubscriptionReconciler(
    private val backend: Backend,
) {
    suspend fun reconcile(userId: String, purchases: List<ReconcilePurchase>): Result<ReconcileOutcome> {
        val txns = purchases.map {
            com.zerosettle.sdk.core.BulkReconcileTransaction(
                purchaseToken = it.purchaseToken, productId = it.productId,
                packageName = it.packageName, signedData = it.originalJson, signature = it.signature,
            )
        }
        return backend.bulkReconcilePlayPurchases(userId, txns).map {
            ReconcileOutcome(processed = it.processed, eventsEmitted = it.eventsEmitted)
        }
    }
}
