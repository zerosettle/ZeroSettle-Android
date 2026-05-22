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
    /**
     * The Play Billing `purchaseToken` for the conflicting purchase, when this
     * claim originated from a Play sync conflict. Nullable: StoreKit-sourced
     * claims and payloads from a backend that predates the token field leave it
     * `null`. Consumed by `ZeroSettle.transferPlayOwnershipToCurrentUser` to key
     * the Play ownership transfer. Additive — non-breaking.
     */
    @SerialName("purchase_token") val purchaseToken: String? = null,
)
