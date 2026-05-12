package com.zerosettle.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single payment record (web checkout, StoreKit sync, or Play sync). Mirrors
 * `GET /v1/iap/transaction-history/` rows + the success payload of a completed
 * checkout. Distinct from [Entitlement] — a transaction is the *event*, an
 * entitlement is the *current state*.
 */
@Serializable
public data class CheckoutTransaction(
    val id: String,
    @SerialName("product_id") val productId: String,
    val status: Status,
    val source: EntitlementSource,
    @SerialName("purchased_at") val purchasedAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("product_name") val productName: String? = null,
    @SerialName("amount_cents") val amountCents: Int? = null,
    val currency: String? = null,
) {
    @Serializable
    public enum class Status {
        @SerialName("completed") COMPLETED,
        @SerialName("pending") PENDING,
        @SerialName("processing") PROCESSING,
        @SerialName("failed") FAILED,
        @SerialName("refunded") REFUNDED,
    }
}
