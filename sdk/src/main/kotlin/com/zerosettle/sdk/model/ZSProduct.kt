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
data class ZSProduct(
    val id: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("product_description")
    val productDescription: String,
    val type: ZSProductType,
    @SerialName("web_price")
    val webPrice: Price,
    @SerialName("storekit_price")
    val appStorePrice: Price? = null,
    @SerialName("synced_to_asc")
    val syncedToASC: Boolean = false,
    val promotion: Promotion? = null,
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
                    return Price(
                        amountMicros = micros.toInt(),
                        currencyCode = webPrice.currencyCode
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
            val psPrice = playStorePrice ?: return null
            if (psPrice.amountMicros <= 0) return null
            val savings =
                (psPrice.amountMicros - webPrice.amountMicros).toDouble() / psPrice.amountMicros
            val percent = (savings * 100).toInt()
            return if (percent > 0) percent else null
        }
}

/**
 * The type of in-app purchase product.
 * String values match iOS raw values for backend compatibility.
 */
@Serializable
enum class ZSProductType {
    @SerialName("auto_renewable_subscription")
    AUTO_RENEWABLE_SUBSCRIPTION,

    @SerialName("non_renewing_subscription")
    NON_RENEWING_SUBSCRIPTION,

    @SerialName("consumable")
    CONSUMABLE,

    @SerialName("non_consumable")
    NON_CONSUMABLE,
}

/**
 * A price with currency information.
 */
@Serializable
data class Price(
    @SerialName("amount_micros")
    val amountMicros: Int,
    @SerialName("currency_code")
    val currencyCode: String,
) {
    /** Formatted price string (e.g., "$9.99"). */
    val formatted: String
        get() {
            val amount = amountMicros / 1_000_000.0
            return try {
                val formatter = NumberFormat.getCurrencyInstance()
                formatter.currency = Currency.getInstance(currencyCode)
                formatter.format(amount)
            } catch (_: Exception) {
                "$currencyCode ${String.format("%.2f", amount)}"
            }
        }
}
