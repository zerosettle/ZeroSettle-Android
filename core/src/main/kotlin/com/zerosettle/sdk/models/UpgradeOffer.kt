package com.zerosettle.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * In-app subscription upgrade / downgrade offer (web → web). Distinct from the
 * unified [UserOffer] surface (the server-decided migration+upgrade offer) — this
 * is the narrower "switch plans within the same group" config the host can present
 * directly via `ZeroSettle.fetchUpgradeOfferConfig()`.
 *
 * TODO(chunk-5): align [Config] with the real `GET /v1/iap/upgrade-offer/` wire
 * shape (`{available, current_product, target_product, savings_percent, upgrade_type,
 * proration, display}` — see `compute_upgrade_offer`). The fields below were the
 * chunk-4 placeholder shape and don't decode today's response.
 *
 * **WIRE-SHAPE DIVERGENCE (audit task #105):** calling
 * [com.zerosettle.sdk.ZeroSettle.fetchUpgradeOfferConfig] today crashes decode
 * with `MissingFieldException` — `from_product_id` / `to_product_id` are
 * required, but the backend emits `current_product` / `target_product` as
 * nested objects. Use the unified [UserOffer] (`fetchUserOffer()`) instead,
 * which DOES match the live wire and covers both upgrade and migration flows.
 */
public object UpgradeOffer {

    @Serializable
    public data class Config(
        @SerialName("from_product_id") val fromProductId: String,
        @SerialName("to_product_id") val toProductId: String,
        @SerialName("savings_percent") val savingsPercent: Int,
        val display: Display,
    )

    /**
     * Presentation copy for the legacy upgrade-offer config (the `display` block of
     * `GET /v1/iap/upgrade-offer/`'s response). Keys are `offer_*` / `accepted_*` /
     * `completed_*` — the legacy shape, distinct from [UserOffer.OfferDisplay].
     */
    @Serializable
    public data class Display(
        @SerialName("offer_title") val offerTitle: String,
        @SerialName("offer_message") val offerMessage: String,
        @SerialName("offer_cta") val offerCta: String,
        @SerialName("accepted_title") val acceptedTitle: String,
        @SerialName("accepted_message") val acceptedMessage: String,
        @SerialName("accepted_cta") val acceptedCta: String,
        @SerialName("completed_title") val completedTitle: String,
        @SerialName("completed_message") val completedMessage: String,
    ) {
        public fun offerTitleOrDefault(fallback: String): String = offerTitle.ifBlank { fallback }
        public fun offerMessageOrDefault(fallback: String): String = offerMessage.ifBlank { fallback }
        public fun offerCtaOrDefault(fallback: String): String = offerCta.ifBlank { fallback }
    }

    public sealed class Result {
        public data class Accepted(val newProductId: String) : Result()
        public data object Dismissed : Result()
        public data class Failed(val error: ZeroSettleError) : Result()
    }
}
