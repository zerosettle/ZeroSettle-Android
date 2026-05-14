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
 * Storefront that owns an entitlement / transaction.
 *
 * [UNKNOWN] is the forward-compat fallback: backend may emit a new source
 * value (e.g., a future storefront, or a misconfiguration leaking the raw
 * server-side enum string) and we'd rather hand callers a value that
 * compares !=  to the three known sources than crash the entire decode.
 * Mirrors the [EntitlementStatus.UNKNOWN] pattern already in this file.
 *
 * The raw wire string for an [UNKNOWN] value is not preserved on the
 * decoded object — that's a deliberate trade-off vs. [EntitlementStatus]
 * (which DOES preserve [Entitlement.statusRaw]) because no consumer in
 * the SDK reads it. Add it if a use case appears.
 */
@Serializable(with = EntitlementSourceSerializer::class)
public enum class EntitlementSource(public val wire: String) {
    STORE_KIT("store_kit"),
    WEB_CHECKOUT("web_checkout"),
    PLAY_STORE("play_store"),
    UNKNOWN("");

    public companion object {
        public fun fromWire(value: String): EntitlementSource =
            values().firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/**
 * String-backed serializer for [EntitlementSource] with an [EntitlementSource.UNKNOWN]
 * fallback for unrecognized wire values. Without this, a decode error on `source`
 * would crash the entire surrounding [Entitlement] / [CheckoutTransaction] decode.
 */
internal object EntitlementSourceSerializer : KSerializer<EntitlementSource> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("EntitlementSource", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): EntitlementSource =
        EntitlementSource.fromWire(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: EntitlementSource) {
        encoder.encodeString(value.wire)
    }
}

/**
 * Entitlement lifecycle status. Unknown values from the backend deserialize as
 * [UNKNOWN] (with the raw string preserved on [Entitlement.statusRaw]) so forward-
 * compat works without crashing.
 */
public enum class EntitlementStatus {
    ACTIVE, PAUSED, EXPIRED, REVOKED, CANCELLED, GRACE_PERIOD, PAST_DUE, UNKNOWN,
}

/**
 * Active-or-recently-active subscription / non-consumable grant for the identified
 * user. Mirrors `GET /v1/iap/entitlements/`. Forward-compatible: unrecognized
 * `status` strings surface as [EntitlementStatus.UNKNOWN] with [statusRaw] intact.
 */
@Serializable
public data class Entitlement(
    val id: String,
    @SerialName("product_id") val productId: String,
    val source: EntitlementSource,
    @SerialName("is_active") val isActive: Boolean,
    // kotlinx-serialization's compiler plugin accesses this private backing field
    // directly — do not "clean up" by inlining it into [status].
    @SerialName("status") private val _statusRaw: String,
    @SerialName("product_type") val productType: String? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("pause_resumes_at") val pauseResumesAt: String? = null,
    @SerialName("grace_period_ends_at") val gracePeriodEndsAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("will_renew") val willRenew: Boolean = false,
    @SerialName("is_trial") val isTrial: Boolean = false,
    @SerialName("trial_ends_at") val trialEndsAt: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("purchased_at") val purchasedAt: String? = null,
    @SerialName("subscription_group_id") val subscriptionGroupId: String? = null,
    // Forward-compat slots — the entitlements endpoint does NOT currently
    // emit these fields (the IDs live on the Transaction row, not on the
    // EntitlementState surface). Kept nullable so the SDK is ready when
    // the backend exposes them.
    @SerialName("storekit_original_transaction_id") val storekitOriginalTransactionId: String? = null,
    @SerialName("play_purchase_token") val playPurchaseToken: String? = null,
) {
    /** Raw status string as sent by the backend (preserved even when unrecognized). */
    val statusRaw: String get() = _statusRaw

    val status: EntitlementStatus get() = when (_statusRaw) {
        "active" -> EntitlementStatus.ACTIVE
        "paused" -> EntitlementStatus.PAUSED
        "expired" -> EntitlementStatus.EXPIRED
        "revoked" -> EntitlementStatus.REVOKED
        "cancelled", "canceled" -> EntitlementStatus.CANCELLED
        "grace_period" -> EntitlementStatus.GRACE_PERIOD
        "past_due" -> EntitlementStatus.PAST_DUE
        else -> EntitlementStatus.UNKNOWN
    }
}
