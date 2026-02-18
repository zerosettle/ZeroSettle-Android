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
    @SerialName("expires_at")
    val expiresAt: String? = null,
    @SerialName("purchased_at")
    val purchasedAt: String,
) {
    /**
     * The origin of a purchase/entitlement.
     * String values match iOS raw values for backend compatibility.
     */
    @Serializable
    enum class Source {
        @SerialName("store_kit")
        STORE_KIT,

        @SerialName("play_store")
        PLAY_STORE,

        @SerialName("web_checkout")
        WEB_CHECKOUT,
    }
}