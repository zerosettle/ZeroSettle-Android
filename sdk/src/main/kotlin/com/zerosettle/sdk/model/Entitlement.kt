package com.zerosettle.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents an active entitlement from either a Play Store or web checkout purchase.
 */
@Serializable
data class Entitlement(
    val id: String,
    @SerialName("product_id")
    val productId: String,
    val source: Source = Source.WEB_CHECKOUT,
    @SerialName("is_active")
    val isActive: Boolean,
    val status: String = "active",
    @SerialName("expires_at")
    val expiresAt: String? = null,
    @SerialName("purchased_at")
    val purchasedAt: String,
    @SerialName("paused_at")
    val pausedAt: String? = null,
    @SerialName("pause_resumes_at")
    val pauseResumesAt: String? = null,
    @SerialName("will_renew")
    val willRenew: Boolean = true,
    @SerialName("is_trial")
    val isTrial: Boolean = false,
    @SerialName("trial_ends_at")
    val trialEndsAt: String? = null,
    @SerialName("cancelled_at")
    val cancelledAt: String? = null,
) {
    /**
     * The origin of a purchase/entitlement.
     *
     * A single user can hold entitlements from multiple sources simultaneously
     * (e.g., a Play Store subscription and a web checkout consumable).
     * [ZeroSettle.showManageSubscription] uses these values to route to the
     * appropriate management UI.
     *
     * String values match iOS raw values for backend compatibility.
     */
    @Serializable
    enum class Source {
        /** Purchased through Apple StoreKit (cross-platform entitlement from an iOS purchase). */
        @SerialName("store_kit")
        STORE_KIT,

        /** Purchased through Google Play Store billing. */
        @SerialName("play_store")
        PLAY_STORE,

        /** Purchased through ZeroSettle's web checkout (Stripe billing). */
        @SerialName("web_checkout")
        WEB_CHECKOUT,
    }
}