package com.zerosettle.sdk.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlin.math.roundToInt

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

/**
 * Monetary value, fixed-precision cents + ISO currency code.
 *
 * **Wire format note:** the backend (`api/iap_views.py`) emits `amount_micros`
 * — cents × 10,000 — on every product/price payload. The custom [PriceSerializer]
 * decodes that wire field and stores it as cents in [amountCents], so callers
 * always work in cents. This matches iOS Kit's `Price.swift` exactly (which
 * uses a custom Codable with the same rename + conversion).
 */
@Serializable(with = PriceSerializer::class)
public data class Price(
    val amountCents: Int,
    val currencyCode: String,
)

/**
 * Bridges the backend's `amount_micros` wire field to [Price.amountCents]
 * (and back, multiplying by 10,000 on encode for round-trip fidelity).
 * Mirrors iOS Kit `Price.swift` (custom Codable with the same shape).
 */
internal object PriceSerializer : KSerializer<Price> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Price") {
        element<Long>("amount_micros")
        element<String>("currency_code")
    }

    override fun deserialize(decoder: Decoder): Price = decoder.decodeStructure(descriptor) {
        var amountMicros: Long = 0
        var currencyCode = ""
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> amountMicros = decodeLongElement(descriptor, 0)
                1 -> currencyCode = decodeStringElement(descriptor, 1)
                CompositeDecoder.DECODE_DONE -> break
                else -> error("Unexpected index $index")
            }
        }
        Price(
            amountCents = (amountMicros.toDouble() / 10_000.0).roundToInt(),
            currencyCode = currencyCode,
        )
    }

    override fun serialize(encoder: Encoder, value: Price) {
        encoder.encodeStructure(descriptor) {
            encodeLongElement(descriptor, 0, value.amountCents.toLong() * 10_000L)
            encodeStringElement(descriptor, 1, value.currencyCode)
        }
    }
}

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
    // Backend wire key is `storekit_price` (not `app_store_price`). iOS Kit
    // does the same rename: `case appStorePrice = "storekitPrice"` in
    // ZSProduct.swift.
    @SerialName("storekit_price") val appStorePrice: Price? = null,
    // Backend does not currently emit `play_store_price`. Field is retained
    // as a nullable forward-compat slot; rename / drop when the catalog
    // serializer in api/iap_views.py:get_products learns to emit it.
    @SerialName("play_store_price") val playStorePrice: Price? = null,
    // Backend wire key is ``synced_to_asc`` (api/iap_views.py:get_products), not
    // ``synced_to_app_store_connect``. The legacy key was never emitted, so
    // this field silently defaulted to ``false`` for every adopter.
    @SerialName("synced_to_asc") val syncedToAppStoreConnect: Boolean = false,
    // Forward-compat slot — the canonical Product catalog response does not
    // currently emit ``billing_interval`` (subscription cadence is derived
    // client-side from product metadata). Kept nullable so the field is
    // available if the backend starts emitting it.
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
