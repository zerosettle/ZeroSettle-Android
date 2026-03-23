package com.zerosettle.sdk.model

import com.android.billingclient.api.ProductDetails
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.text.NumberFormat
import java.util.Currency

/**
 * A product available for web checkout via ZeroSettle.
 * The `id` matches the Play Store product identifier configured on the ZeroSettle dashboard.
 */
@Serializable
data class Product(
    val id: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("product_description")
    val productDescription: String,
    val type: ProductType,
    @SerialName("web_price")
    val webPrice: Price? = null,
    @SerialName("storekit_price")
    val appStorePrice: Price? = null,
    @SerialName("synced_to_asc")
    val syncedToAppStoreConnect: Boolean = false,
    val promotion: Promotion? = null,
    @SerialName("subscription_group_id")
    val subscriptionGroupId: Int? = null,
    @SerialName("billing_interval")
    val billingInterval: String? = null,
    @SerialName("free_trial_duration")
    val freeTrialDuration: String? = null,
    @SerialName("is_trial_eligible")
    val isTrialEligible: Boolean? = null,
) {
    /** The underlying Play Store product (populated after reconciliation). Not serialized. */
    @Transient
    var playStoreProduct: ProductDetails? = null
        internal set

    /** Whether Play Store purchase is available for this product. */
    val playStoreAvailable: Boolean get() = playStoreProduct != null

    /**
     * Play Store price — prefers on-device fetch, falls back to backend price for display.
     */
    val playStorePrice: Price?
        get() {
            val details = playStoreProduct
            if (details != null) {
                // Try one-time purchase price first, then subscription
                val oneTimeMicros = details.oneTimePurchaseOfferDetails?.priceAmountMicros
                val subMicros = details.subscriptionOfferDetails?.firstOrNull()
                    ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.priceAmountMicros

                val micros = oneTimeMicros ?: subMicros
                if (micros != null) {
                    val currency = webPrice?.currencyCode ?: appStorePrice?.currencyCode ?: "USD"
                    return Price(
                        amountCents = (micros / 10_000).toInt(),
                        currencyCode = currency
                    )
                }
            }
            return appStorePrice
        }

    /**
     * The percentage savings of the web price compared to the Play Store price.
     * Returns null if the Play Store price isn't available or web is more expensive.
     */
    val savingsPercent: Int?
        get() {
            val wp = webPrice ?: return null
            val psPrice = playStorePrice ?: return null
            if (psPrice.amountCents <= 0) return null
            val savings =
                (psPrice.amountCents - wp.amountCents).toDouble() / psPrice.amountCents
            val percent = (savings * 100).toInt()
            return if (percent > 0) percent else null
        }

    /** Parsed free trial duration in days. Null when no trial or user ineligible. */
    val freeTrialDays: Int?
        get() {
            if (isTrialEligible != true) return null
            val duration = freeTrialDuration ?: return null
            return parseFreeTrialDays(duration)
        }

    /** Human-readable free trial label (e.g. "1-week free trial"). */
    val freeTrialLabel: String?
        get() {
            if (isTrialEligible != true) return null
            val duration = freeTrialDuration ?: return null
            return formatFreeTrialLabel(duration)
        }

    companion object {
        private fun parseFreeTrialDays(raw: String): Int? {
            val lowered = raw.lowercase()
            if (lowered.startsWith("p")) {
                Regex("(\\d+)d").find(lowered)?.let { return it.groupValues[1].toIntOrNull() }
                Regex("(\\d+)w").find(lowered)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) * 7 }
                Regex("(\\d+)m").find(lowered)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) * 30 }
            }
            val parts = lowered.split("_")
            if (parts.size == 2) {
                val num = parts[0].toIntOrNull() ?: return null
                return when (parts[1].removeSuffix("s")) {
                    "day" -> num
                    "week" -> num * 7
                    "month" -> num * 30
                    else -> num
                }
            }
            return raw.toIntOrNull()
        }

        private fun formatFreeTrialLabel(raw: String): String? {
            val lowered = raw.lowercase()
            if (lowered.startsWith("p")) {
                Regex("(\\d+)d").find(lowered)?.let { return "${it.groupValues[1]}-day free trial" }
                Regex("(\\d+)w").find(lowered)?.let {
                    val n = it.groupValues[1].toIntOrNull() ?: 0
                    return if (n == 1) "1-week free trial" else "$n-week free trial"
                }
                Regex("(\\d+)m").find(lowered)?.let {
                    val n = it.groupValues[1].toIntOrNull() ?: 0
                    return if (n == 1) "1-month free trial" else "$n-month free trial"
                }
            }
            val parts = lowered.split("_")
            if (parts.size == 2) {
                val num = parts[0].toIntOrNull() ?: return null
                val unit = parts[1].removeSuffix("s")
                return "$num-$unit free trial"
            }
            raw.toIntOrNull()?.let { return "$it-day free trial" }
            return null
        }
    }

    /**
     * The type of in-app purchase product.
     * String values match iOS raw values for backend compatibility.
     */
    @Serializable
    enum class ProductType {
        @SerialName("auto_renewable_subscription")
        AUTO_RENEWABLE_SUBSCRIPTION,

        @SerialName("non_renewing_subscription")
        NON_RENEWING_SUBSCRIPTION,

        @SerialName("consumable")
        CONSUMABLE,

        @SerialName("non_consumable")
        NON_CONSUMABLE,
    }
}

/** Backward-compatible typealias. */
@Deprecated("Use Product", ReplaceWith("Product"))
typealias ZSProduct = Product

/** Top-level convenience alias for Product.ProductType. */
typealias ProductType = Product.ProductType
/**
 * A price with currency information.
 *
 * `amountCents` is the price in cents (e.g., 999 = $9.99).
 * The backend sends `amount_micros` on the wire; the SDK converts micros to cents
 * during deserialization via a custom setter so callers always work in cents.
 */
@Serializable
data class Price(
    @SerialName("amount_micros")
    val amountCents: Int,
    @SerialName("currency_code")
    val currencyCode: String,
) {
    /** Formatted price string (e.g., "$9.99"). */
    val formatted: String
        get() {
            val amount = amountCents / 100.0
            return try {
                val formatter = NumberFormat.getCurrencyInstance()
                formatter.currency = Currency.getInstance(currencyCode)
                formatter.format(amount)
            } catch (_: Exception) {
                "$currencyCode ${String.format("%.2f", amount)}"
            }
        }
}
