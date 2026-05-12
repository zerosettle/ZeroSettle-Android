package com.zerosettle.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class EntitlementSource {
    @SerialName("store_kit") STORE_KIT,
    @SerialName("web_checkout") WEB_CHECKOUT,
    @SerialName("play_store") PLAY_STORE,
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
