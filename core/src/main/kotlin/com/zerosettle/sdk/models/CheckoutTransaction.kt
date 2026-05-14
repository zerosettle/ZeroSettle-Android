package com.zerosettle.sdk.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
    /**
     * Lifecycle state for a transaction record.
     *
     * Wire-mapped 1:1 from `api/iap_views.py` `get_transaction` / `get_transaction_history`:
     * - `completed` / `pending` / `processing` / `failed` / `refunded` are the
     *   classic states.
     * - `superseded` is emitted when a follow-up transaction supersedes this
     *   one (e.g., a web→web plan switch cancels the old subscription's
     *   transaction). Adding the case so a SUPERSEDED row no longer crashes
     *   decode for the entire surrounding response.
     * - [UNKNOWN] is the forward-compat fallback for any future status the
     *   backend may add; decode succeeds, callers see `UNKNOWN`.
     */
    @Serializable(with = StatusSerializer::class)
    public enum class Status(public val wire: String) {
        COMPLETED("completed"),
        PENDING("pending"),
        PROCESSING("processing"),
        FAILED("failed"),
        REFUNDED("refunded"),
        SUPERSEDED("superseded"),
        UNKNOWN("");

        public companion object {
            public fun fromWire(value: String): Status =
                values().firstOrNull { it.wire == value } ?: UNKNOWN
        }
    }

    internal object StatusSerializer : KSerializer<Status> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("CheckoutTransaction.Status", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Status =
            Status.fromWire(decoder.decodeString())

        override fun serialize(encoder: Encoder, value: Status) {
            encoder.encodeString(value.wire)
        }
    }
}
