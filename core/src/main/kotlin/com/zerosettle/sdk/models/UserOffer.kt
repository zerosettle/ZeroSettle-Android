package com.zerosettle.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Unified `GET /v1/iap/user-offer/` response schema. Mirrors iOS `UserOffer.*`
 * and the backend `api.services.user_offer.types` dataclasses (the latter is
 * authoritative — it carries `max_subscription_days`, `rollout_percent`, and the
 * nested `source` field that earlier iOS builds lacked).
 *
 * The wire shape is:
 * ```jsonc
 * { "user_id", "app_id", "is_sandbox", "subscription": {...}, "offer": {...}, "server_time" }
 * ```
 * — `subscription` is a tagged union keyed by `"type"`; `offer` is [OfferData].
 *
 * All `Json` decoding must run with `ignoreUnknownKeys = true` (forward-compat:
 * the backend adds fields without an SDK release).
 */
public object UserOffer {

    /** Top-level response from `GET /v1/iap/user-offer/`. */
    @Serializable
    public data class Response(
        @SerialName("user_id") val userId: String,
        @SerialName("app_id") val appId: Int,
        @SerialName("is_sandbox") val isSandbox: Boolean,
        val subscription: Subscription,
        val offer: OfferData,
        @SerialName("server_time") val serverTime: String,
    ) {
        /** Convenience: true when the resolved offer is actionable. */
        public val isEligible: Boolean get() = offer.isEligible

        /** Convenience: the offer iff [OfferData.isEligible], else null. */
        public val eligibleOffer: OfferData? get() = offer.takeIf { it.isEligible && it.actionType != ActionType.NO_ACTION }
    }

    /**
     * The user's current subscription state. Tagged union keyed by wire `"type"`.
     * Unknown `type` values decode to [Unknown] for forward compatibility — the
     * surrounding [Response] still decodes.
     */
    @Serializable
    public data class Subscription(
        val type: String,
        @SerialName("product_id") val productId: String? = null,
    ) {
        public val kind: Kind
            get() = when (type) {
                "none" -> Kind.NONE
                "active_web" -> Kind.ACTIVE_WEB
                "active_storekit" -> Kind.ACTIVE_STOREKIT
                "migration_trial" -> Kind.MIGRATION_TRIAL
                "cancelled_active" -> Kind.CANCELLED_ACTIVE
                else -> Kind.UNKNOWN
            }

        public enum class Kind { NONE, ACTIVE_WEB, ACTIVE_STOREKIT, MIGRATION_TRIAL, CANCELLED_ACTIVE, UNKNOWN }
    }

    /**
     * Canonical offer payload (`offer` in the response). [actionType] discriminates
     * between no-action, the StoreKit→web migration, and the two upgrade flows.
     *
     * Nullable/defaulted fields mirror the backend: it sends `null` (or omits) for
     * [display], [proration], [appleSubscription], [checkoutPresentation],
     * [experimentVariantId], [fromProductId], [maxSubscriptionDays], and [source].
     * [isEligible] is required (no default).
     */
    @Serializable
    public data class OfferData(
        @SerialName("action_type") val actionType: ActionType,
        @SerialName("is_eligible") val isEligible: Boolean,
        @SerialName("checkout_product_id") val checkoutProductId: String,
        @SerialName("from_product_id") val fromProductId: String? = null,
        @SerialName("savings_percent") val savingsPercent: Int = 0,
        @SerialName("free_trial_days") val freeTrialDays: Int = 0,
        @SerialName("min_subscription_days") val minSubscriptionDays: Int = 0,
        @SerialName("max_subscription_days") val maxSubscriptionDays: Int? = null,
        @SerialName("rollout_percent") val rolloutPercent: Int = 100,
        val display: OfferDisplay? = null,
        val proration: OfferProration? = null,
        @SerialName("requires_apple_cancel") val requiresAppleCancel: Boolean = false,
        @SerialName("apple_subscription") val appleSubscription: AppleSubscriptionSummary? = null,
        @SerialName("checkout_presentation") val checkoutPresentation: CheckoutPresentation? = null,
        @SerialName("experiment_variant_id") val experimentVariantId: Int? = null,
        /**
         * Storefront the offer's *source* subscription lives on. Only meaningful
         * for `MIGRATE_STOREKIT_TO_WEB` / `UPGRADE_STOREKIT_TO_WEB`. The backend
         * omits this key entirely when null, so this is nullable with no default.
         */
        val source: SourceStorefront? = null,
    ) {
        /**
         * True when accepting this offer requires the host SDK to cancel the user's
         * store subscription afterward. Equals the server's [requiresAppleCancel] —
         * surfaced as a named accessor for call-site clarity.
         */
        public val needsStoreCancel: Boolean get() = requiresAppleCancel

        /** True when there is no WebView checkout (server-side Stripe plan switch). */
        public val isHeadlessUpgrade: Boolean get() = actionType == ActionType.UPGRADE_WEB_TO_WEB
    }

    /** Discriminator for the resolved action. Matches backend `OfferActionType`. */
    @Serializable
    public enum class ActionType {
        @SerialName("no_action") NO_ACTION,
        @SerialName("migrate_storekit_to_web") MIGRATE_STOREKIT_TO_WEB,
        @SerialName("upgrade_storekit_to_web") UPGRADE_STOREKIT_TO_WEB,
        @SerialName("upgrade_web_to_web") UPGRADE_WEB_TO_WEB,
        /**
         * Play Store → web checkout migration via Google's External Content Link (ECL)
         * billing program. The SDK routes `acceptOffer(activity)` to
         * `ZeroSettle.launchSwitchAndSave(activity)` for this action type.
         * Only surfaced when [ExternalContentLinkClient.isAvailable] is true.
         */
        @SerialName("migrate_play_to_web") MIGRATE_PLAY_TO_WEB,
    }

    /** Storefront a migration/upgrade offer's source subscription lives on. */
    @Serializable
    public enum class SourceStorefront {
        @SerialName("store_kit") STORE_KIT,
        @SerialName("play_store") PLAY_STORE,
    }

    /**
     * Presentation copy for every offer lifecycle state. All strings are already
     * localized and template-interpolated server-side. Matches backend `OfferDisplay`.
     */
    @Serializable
    public data class OfferDisplay(
        val title: String,
        val body: String,
        @SerialName("cta_text") val ctaText: String,
        @SerialName("dismiss_text") val dismissText: String,
        @SerialName("accepted_title") val acceptedTitle: String,
        @SerialName("accepted_body") val acceptedBody: String,
        @SerialName("completed_title") val completedTitle: String,
        @SerialName("completed_body") val completedBody: String,
        @SerialName("apple_cancel_instructions") val appleCancelInstructions: String = "",
    )

    /** Stripe proration preview for web-to-web upgrades. Matches backend `OfferProration`. */
    @Serializable
    public data class OfferProration(
        @SerialName("amount_cents") val amountCents: Int,
        val currency: String,
        @SerialName("next_billing_date") val nextBillingDate: String? = null,
    )

    /** Normalized Apple subscription status. Only present for StoreKit-source offers. */
    @Serializable
    public data class AppleSubscriptionSummary(
        @SerialName("is_active") val isActive: Boolean,
        @SerialName("expires_at") val expiresAt: String? = null,
        @SerialName("status_code") val statusCode: Int,
        @SerialName("auto_renew_enabled") val autoRenewEnabled: Boolean,
    )

    /** How the server recommends presenting checkout when the CTA is tapped. */
    @Serializable
    public enum class CheckoutPresentation {
        @SerialName("webview") WEBVIEW,
        @SerialName("native_pay") NATIVE_PAY,
        @SerialName("safari_vc") SAFARI_VC,
        @SerialName("safari") SAFARI,
    }
}
