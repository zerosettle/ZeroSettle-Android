package com.zerosettle.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Upgrade offer configuration returned by the backend.
 * Controls whether to present the upgrade offer sheet and its content.
 */
@Serializable
data class UpgradeOfferConfig(
    val available: Boolean,
    val reason: String? = null,
    @SerialName("current_product") val currentProduct: UpgradeOfferCurrentProduct? = null,
    @SerialName("target_product") val targetProduct: UpgradeOfferTargetProduct? = null,
    @SerialName("savings_percent") val savingsPercent: Int? = null,
    @SerialName("upgrade_type") val upgradeType: UpgradeOfferType? = null,
    val proration: UpgradeOfferProration? = null,
    val display: UpgradeOfferDisplay? = null,
)

/**
 * The user's current subscription product in an upgrade offer.
 */
@Serializable
data class UpgradeOfferCurrentProduct(
    @SerialName("reference_id") val referenceId: String,
    val name: String,
    @SerialName("price_cents") val priceCents: Int,
    val currency: String,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("billing_label") val billingLabel: String,
)

/**
 * The target upgrade product in an upgrade offer.
 */
@Serializable
data class UpgradeOfferTargetProduct(
    @SerialName("reference_id") val referenceId: String,
    val name: String,
    @SerialName("price_cents") val priceCents: Int,
    val currency: String,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("billing_label") val billingLabel: String,
    @SerialName("monthly_equivalent_cents") val monthlyEquivalentCents: Int,
)

/**
 * The type of upgrade path.
 */
@Serializable
enum class UpgradeOfferType {
    @SerialName("web_to_web")
    WEB_TO_WEB,

    @SerialName("storekit_to_web")
    STOREKIT_TO_WEB,
}

/**
 * Proration details for the upgrade.
 */
@Serializable
data class UpgradeOfferProration(
    @SerialName("proration_amount_cents") val prorationAmountCents: Int,
    val currency: String,
    @SerialName("next_billing_date") val nextBillingDate: Long? = null,
)

/**
 * Display strings for the upgrade offer sheet.
 */
@Serializable
data class UpgradeOfferDisplay(
    val title: String,
    val body: String,
    @SerialName("cta_text") val ctaText: String,
    @SerialName("dismiss_text") val dismissText: String,
    @SerialName("storekit_migration_body") val storekitMigrationBody: String? = null,
    @SerialName("cancel_instructions") val cancelInstructions: String? = null,
)

/**
 * The outcome of an upgrade offer presentation.
 * Maps to iOS `UpgradeOffer.Result`.
 */
sealed interface UpgradeOfferResult {
    /** The user accepted the upgrade and it was executed. */
    data object Upgraded : UpgradeOfferResult

    /** The user explicitly declined the upgrade offer. */
    data object Declined : UpgradeOfferResult

    /** The user dismissed the sheet without making a choice. */
    data object Dismissed : UpgradeOfferResult
}

/** Wire-safe name for an [UpgradeOfferResult], used for backend payloads and logging. */
val UpgradeOfferResult.outcomeName: String
    get() = when (this) {
        is UpgradeOfferResult.Upgraded -> "upgraded"
        is UpgradeOfferResult.Declined -> "declined"
        is UpgradeOfferResult.Dismissed -> "dismissed"
    }
