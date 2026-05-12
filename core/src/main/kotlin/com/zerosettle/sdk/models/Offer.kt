package com.zerosettle.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Unified offer model. Mirrors iOS `Offer.*` (NOT the deprecated migration-only
 * model). Single namespace for both [FlowType.MIGRATION] and [FlowType.UPGRADE].
 */
public object Offer {

    @Serializable
    public enum class FlowType {
        @SerialName("migration") MIGRATION,
        @SerialName("upgrade") UPGRADE,
    }

    @Serializable
    public enum class UpgradeType {
        @SerialName("storekit_to_web") STOREKIT_TO_WEB,
        @SerialName("web_to_web") WEB_TO_WEB,
        @SerialName("play_to_web") PLAY_TO_WEB,
    }

    @Serializable
    public enum class SourceStorefront {
        @SerialName("store_kit") STORE_KIT,
        @SerialName("play_store") PLAY_STORE,
    }

    @Serializable
    public enum class CheckoutPresentation {
        @SerialName("inline") INLINE,
        @SerialName("sheet") SHEET,
        @SerialName("custom_tab") CUSTOM_TAB,
        @SerialName("browser") BROWSER,
    }

    /** Lifecycle state the host app observes. */
    public enum class State { LOADING, INELIGIBLE, ELIGIBLE, PRESENTED, ACCEPTED, COMPLETED, DISMISSED }

    @Serializable
    public data class OfferDisplay(
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
        public fun acceptedTitleOrDefault(fallback: String): String = acceptedTitle.ifBlank { fallback }
        public fun acceptedMessageOrDefault(fallback: String): String = acceptedMessage.ifBlank { fallback }
        public fun acceptedCtaOrDefault(fallback: String): String = acceptedCta.ifBlank { fallback }
        public fun completedTitleOrDefault(fallback: String): String = completedTitle.ifBlank { fallback }
        public fun completedMessageOrDefault(fallback: String): String = completedMessage.ifBlank { fallback }
    }

    @Serializable
    public data class PerProductOffer(
        @SerialName("product_id") val productId: String,
        @SerialName("savings_percent") val savingsPercent: Int,
        val display: OfferDisplay,
    )

    @Serializable
    public data class OfferData(
        @SerialName("flow_type") val flowType: FlowType,
        @SerialName("upgrade_type") val upgradeType: UpgradeType? = null,
        @SerialName("source_storefront") val sourceStorefront: SourceStorefront,
        @SerialName("product_id") val productId: String,
        @SerialName("eligible_product_ids") val eligibleProductIds: List<String>,
        @SerialName("savings_percent") val savingsPercent: Int,
        val display: OfferDisplay,
        @SerialName("free_trial_days") val freeTrialDays: Int,
        @SerialName("min_subscription_days") val minSubscriptionDays: Int,
        @SerialName("max_subscription_days") val maxSubscriptionDays: Int? = null,
        @SerialName("rollout_percent") val rolloutPercent: Int = 100,
        @SerialName("checkout_presentation") val checkoutPresentation: CheckoutPresentation? = null,
        @SerialName("from_product_id") val fromProductId: String? = null,
        @SerialName("to_product_id") val toProductId: String? = null,
        @SerialName("variant_id") val variantId: Int? = null,
        @SerialName("per_product_prompts") val perProductPrompts: Map<String, PerProductOffer>? = null,
        @SerialName("disclosure_text") val disclosureText: String? = null,
    ) {
        /**
         * True when accepting the offer requires the SDK / backend to cancel the
         * user's store subscription afterward:
         *  - migrations (StoreKit to web, Play to web): yes
         *  - web to web upgrade: no
         *  - storekit-to-web / play-to-web upgrades: yes
         */
        public val needsStoreCancel: Boolean
            get() = flowType == FlowType.MIGRATION ||
                upgradeType == UpgradeType.STOREKIT_TO_WEB ||
                upgradeType == UpgradeType.PLAY_TO_WEB
    }
}
