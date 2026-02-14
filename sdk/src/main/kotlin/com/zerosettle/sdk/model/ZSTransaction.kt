package com.zerosettle.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a completed or pending purchase transaction.
 */
@Serializable
data class ZSTransaction(
    val id: String,
    @SerialName("product_id")
    val productId: String,
    val status: TransactionStatus,
    val source: EntitlementSource,
    @SerialName("purchased_at")
    val purchasedAt: String,
    @SerialName("expires_at")
    val expiresAt: String? = null,
)

/**
 * The status of a transaction.
 * String values match iOS raw values for backend compatibility.
 */
@Serializable
enum class TransactionStatus {
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
