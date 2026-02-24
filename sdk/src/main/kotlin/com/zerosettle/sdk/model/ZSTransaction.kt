package com.zerosettle.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a completed or pending purchase transaction.
 */
@Serializable
data class CheckoutTransaction(
    val id: String,
    @SerialName("product_id")
    val productId: String,
    val status: Status,
    val source: Entitlement.Source,
    @SerialName("purchased_at")
    val purchasedAt: String,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    @SerialName("product_name")
    val productName: String? = null,
    @SerialName("amount_cents")
    val amountCents: Int? = null,
    val currency: String? = null,
) {
    /**
     * The status of a transaction.
     * String values match iOS raw values for backend compatibility.
     */
    @Serializable
    enum class Status {
        @SerialName("completed")
        COMPLETED,

        @SerialName("pending")
        PENDING,

        @SerialName("processing")
        PROCESSING,

        @SerialName("failed")
        FAILED,

        @SerialName("refunded")
        REFUNDED,
    }
}

/** Backward-compatible typealias. */
@Deprecated("Use CheckoutTransaction", ReplaceWith("CheckoutTransaction"))
typealias ZSTransaction = CheckoutTransaction