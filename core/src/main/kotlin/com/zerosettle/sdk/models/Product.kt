package com.zerosettle.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class BillingInterval {
    @SerialName("week") WEEK,
    @SerialName("month") MONTH,
    @SerialName("year") YEAR,
}

@Serializable
public enum class ProductType {
    @SerialName("auto_renewable_subscription") AUTO_RENEWABLE_SUBSCRIPTION,
    @SerialName("non_renewing_subscription") NON_RENEWING_SUBSCRIPTION,
    @SerialName("consumable") CONSUMABLE,
    @SerialName("non_consumable") NON_CONSUMABLE,
}

/** Monetary value, fixed-precision cents + ISO currency code. */
@Serializable
public data class Price(
    @SerialName("amount_cents") val amountCents: Int,
    @SerialName("currency_code") val currencyCode: String,
)

/**
 * Canonical product with storefront-specific prices. Mirrors iOS `ZSProduct`.
 *
 * Any of [webPrice] / [appStorePrice] / [playStorePrice] may be null — opt-in per
 * storefront per chunk 2's catalog work.
 */
@Serializable
public data class Product(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("product_description") val productDescription: String,
    val type: ProductType,
    @SerialName("web_price") val webPrice: Price? = null,
    @SerialName("app_store_price") val appStorePrice: Price? = null,
    @SerialName("play_store_price") val playStorePrice: Price? = null,
    @SerialName("synced_to_app_store_connect") val syncedToAppStoreConnect: Boolean = false,
    @SerialName("billing_interval") val billingInterval: BillingInterval? = null,
    @SerialName("subscription_group_id") val subscriptionGroupId: Int? = null,
    @SerialName("free_trial_duration") val freeTrialDuration: String? = null,
    @SerialName("is_trial_eligible") val isTrialEligible: Boolean? = null,
    @SerialName("play_product_id") val playProductId: String? = null,
    @SerialName("play_base_plan_id") val playBasePlanId: String? = null,
)

@Serializable
public data class ProductCatalog(
    val products: List<Product>,
)
