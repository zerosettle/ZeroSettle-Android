package com.zerosettle.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cross-account ownership conflict: a Play / StoreKit purchase another ZeroSettle
 * account currently holds, which the current user can explicitly claim. Mirrors iOS
 * `PendingClaim`. Surfaced via `ZeroSettle.pendingClaims`.
 *
 * Per the project's "no auto-claim" rule, the SDK never transfers ownership on its
 * own — the host app must call `claimEntitlement(...)` with user intent.
 */
@Serializable
public data class PendingClaim(
    @SerialName("product_id") val productId: String,
    @SerialName("original_transaction_id") val originalTransactionId: String,
    @SerialName("existing_owner_hint") val existingOwnerHint: String,
)
