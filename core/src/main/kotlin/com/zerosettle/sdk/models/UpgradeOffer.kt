package com.zerosettle.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * In-app subscription upgrade offer — the `GET /v1/iap/upgrade-offer/`
 * response (`compute_upgrade_offer`). Presented directly via
 * `ZeroSettle.fetchUpgradeOfferConfig()`.
 *
 * Distinct from the unified [UserOffer] surface: [UserOffer] carries product
 * references as bare ids, whereas [Config] carries full [ProductInfo] objects
 * (name, price, billing label) for direct presentation.
 *
 * Mirrors iOS `ZeroSettleKit`'s `UpgradeOffer` chunk-5 wire shape — every
 * field except [Config.available] is optional so a not-available response
 * (`{"available": false, "reason": ...}`) decodes cleanly.
 */
public object UpgradeOffer {

    /** Configuration returned by the backend describing an available upgrade. */
    @Serializable
    public data class Config(
        /** Whether an upgrade offer is available for this user/product. */
        val available: Boolean,
        /** Reason the offer is unavailable (e.g. `already_highest_tier`); null when available. */
        val reason: String? = null,
        /** The user's current subscription product. */
        @SerialName("current_product") val currentProduct: ProductInfo? = null,
        /** The upgrade target product. */
        @SerialName("target_product") val targetProduct: ProductInfo? = null,
        /** Savings percentage (0–100). */
        @SerialName("savings_percent") val savingsPercent: Int? = null,
        /** The upgrade path: `web_to_web` or `storekit_to_web`. */
        @SerialName("upgrade_type") val upgradeType: String? = null,
        /** Proration details for web-to-web upgrades. */
        val proration: Proration? = null,
        /** Display messaging customized from the dashboard. */
        val display: Display? = null,
        /** A/B experiment variant identifier, if this config is part of an experiment. */
        @SerialName("variant_id") val variantId: Int? = null,
    )

    /** Info about a subscription product in an upgrade offer (current or target). */
    @Serializable
    public data class ProductInfo(
        @SerialName("reference_id") val referenceId: String,
        val name: String,
        @SerialName("price_cents") val priceCents: Int,
        val currency: String,
        @SerialName("billing_label") val billingLabel: String,
        /** Monthly-equivalent price for annual/multi-month plans; null for the current product. */
        @SerialName("monthly_equivalent_cents") val monthlyEquivalentCents: Int? = null,
    )

    /** Proration details for web-to-web upgrades. */
    @Serializable
    public data class Proration(
        /** Credit or charge amount in cents (negative = credit). */
        @SerialName("amount_cents") val amountCents: Int,
        val currency: String,
        /** Next billing date after the upgrade, ISO-8601. */
        @SerialName("next_billing_date") val nextBillingDate: String? = null,
    )

    /** Messaging to display in the upgrade offer sheet. */
    @Serializable
    public data class Display(
        val title: String,
        val body: String,
        @SerialName("cta_text") val ctaText: String,
        @SerialName("dismiss_text") val dismissText: String,
        /** Additional body text for StoreKit→web migrations. */
        @SerialName("storekit_migration_body") val storekitMigrationBody: String? = null,
        /** Instructions for cancelling the Apple subscription. */
        @SerialName("storekit_cancel_instructions") val storekitCancelInstructions: String? = null,
    )

    public sealed class Result {
        public data class Accepted(val newProductId: String) : Result()
        public data object Dismissed : Result()
        public data class Failed(val error: ZeroSettleError) : Result()
    }
}
