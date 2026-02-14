package com.zerosettle.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An active promotion for a product, configured on the ZeroSettle dashboard.
 */
@Serializable
data class Promotion(
    val id: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("promotional_price")
    val promotionalPrice: Price,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    val type: PromotionType,
)

/**
 * The type of promotional discount.
 * String values match iOS raw values for backend compatibility.
 */
@Serializable
enum class PromotionType {
    @SerialName("percent_off")
    PERCENT_OFF,

    @SerialName("fixed_amount")
    FIXED_AMOUNT,

    @SerialName("free_trial")
    FREE_TRIAL,
}
