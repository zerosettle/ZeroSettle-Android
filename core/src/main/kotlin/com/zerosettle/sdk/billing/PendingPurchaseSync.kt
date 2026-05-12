package com.zerosettle.sdk.billing

import kotlinx.serialization.Serializable

/**
 * One Play purchase whose backend sync failed and is queued for retry. Persisted
 * by [PlaySyncQueue] (DataStore, JSON-encoded). [purchaseToken] is the dedup key.
 *
 * Mirrors iOS `StoreKitSyncQueue.PendingSync` (which keys on `transactionId`).
 */
@Serializable
public data class PendingPurchaseSync(
    val purchaseToken: String,
    val productId: String,
    val packageName: String,
    val userId: String,
    val orderId: String? = null,
    val signature: String? = null,
    val originalJson: String? = null,
    val attemptCount: Int = 0,
    val lastAttemptAtMillis: Long? = null,
)
