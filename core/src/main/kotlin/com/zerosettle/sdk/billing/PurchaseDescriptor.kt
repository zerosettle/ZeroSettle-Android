package com.zerosettle.sdk.billing

/**
 * Storefront-agnostic view of a Play `Purchase`, decoupling [PurchaseSyncProcessor]
 * from `com.android.billingclient.api.Purchase` so the processor is unit-testable
 * without the Billing library. [PlayBillingManager] (Task 26) builds this from a
 * real `Purchase`.
 */
public data class PurchaseDescriptor(
    val purchaseToken: String,
    val productId: String,
    val packageName: String,
    val userId: String,
    val orderId: String?,
    /** `Purchase.PurchaseState`: 0 UNSPECIFIED, 1 PURCHASED, 2 PENDING. */
    val purchaseState: Int,
    val isAcknowledged: Boolean,
    val signature: String,
    val originalJson: String,
    val willAutoRenew: Boolean,
    val customerName: String?,
    val customerEmail: String?,
)
