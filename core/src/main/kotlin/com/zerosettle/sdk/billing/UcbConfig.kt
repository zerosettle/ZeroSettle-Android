package com.zerosettle.sdk.billing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tenant-level UCB configuration fetched from the backend at SDK launch.
 *
 * Drives whether the [BillingClient][com.android.billingclient.api.BillingClient]
 * gets `enableUserChoiceBilling(...)` vs `enableAlternativeBillingOnly()` vs
 * standard Play Billing. Google enforces market eligibility itself — the SDK
 * does not gate by region/date. See `docs/superpowers/plans/2026-05-12-ucb-implementation-plan.md`.
 */
@Serializable
public data class UcbConfig(
    @SerialName("is_enabled") val isEnabled: Boolean = false,
    @SerialName("dma_alt_billing_only_eea") val dmaAltBillingOnlyEea: Boolean = false,
    @SerialName("logo_banner_url") val logoBannerUrl: String = "",
    @SerialName("subscription_management_urls") val subscriptionManagementUrls: Map<String, String> = emptyMap(),
) {
    public companion object {
        /** Disabled-by-default fallback when the backend hasn't shipped the endpoint yet. */
        public val Disabled: UcbConfig = UcbConfig()
    }
}
