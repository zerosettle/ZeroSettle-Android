package com.zerosettle.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * In-app subscription upgrade / downgrade offer (web → web). Distinct from
 * [Offer] (the unified migration+upgrade offer surface) — this is the narrower
 * "switch plans within the same group" config the host can present directly.
 */
public object UpgradeOffer {

    @Serializable
    public data class Config(
        @SerialName("from_product_id") val fromProductId: String,
        @SerialName("to_product_id") val toProductId: String,
        @SerialName("savings_percent") val savingsPercent: Int,
        val display: Offer.OfferDisplay,
    )

    public sealed class Result {
        public data class Accepted(val newProductId: String) : Result()
        public data object Dismissed : Result()
        public data class Failed(val error: ZeroSettleError) : Result()
    }
}
