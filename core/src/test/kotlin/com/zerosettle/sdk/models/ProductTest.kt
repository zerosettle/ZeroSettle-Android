package com.zerosettle.sdk.models

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ProductTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // Wire shape mirrors api/iap_views.py:get_products exactly: prices are
    // `amount_micros` (cents × 10,000), App Store price ships under the wire
    // key `storekit_price`. iOS Kit decodes the same payload — diverging here
    // means the SDK fails decode against the live backend.
    private val fixture = """
    {
      "id": "pro_monthly",
      "display_name": "Pro Monthly",
      "product_description": "Unlock everything",
      "type": "auto_renewable_subscription",
      "web_price": { "amount_micros": 4990000, "currency_code": "USD" },
      "storekit_price": { "amount_micros": 5990000, "currency_code": "USD" },
      "play_store_price": { "amount_micros": 5490000, "currency_code": "USD" },
      "synced_to_app_store_connect": true,
      "billing_interval": "month",
      "subscription_group_id": 7,
      "free_trial_duration": "P7D",
      "is_trial_eligible": true,
      "play_product_id": "pro_monthly_play",
      "play_base_plan_id": "monthly-autorenew"
    }
    """.trimIndent()

    @Test fun decode_fromFixture_populatesAllFields() {
        val p = json.decodeFromString(Product.serializer(), fixture)
        assertThat(p.id).isEqualTo("pro_monthly")
        assertThat(p.displayName).isEqualTo("Pro Monthly")
        assertThat(p.type).isEqualTo(ProductType.AUTO_RENEWABLE_SUBSCRIPTION)
        assertThat(p.webPrice?.amountCents).isEqualTo(499)
        assertThat(p.webPrice?.currencyCode).isEqualTo("USD")
        assertThat(p.appStorePrice?.amountCents).isEqualTo(599)
        assertThat(p.playStorePrice?.amountCents).isEqualTo(549)
        assertThat(p.billingInterval).isEqualTo(BillingInterval.MONTH)
        assertThat(p.freeTrialDuration).isEqualTo("P7D")
        assertThat(p.isTrialEligible).isTrue()
        assertThat(p.playProductId).isEqualTo("pro_monthly_play")
        assertThat(p.playBasePlanId).isEqualTo("monthly-autorenew")
    }

    @Test fun decode_priceRoundTrip_micros() {
        // Pin the micros↔cents conversion explicitly — the easy regression
        // case is "forget to divide by 10_000 on decode" which would make
        // amountCents=4_990_000 (i.e., $49,900.00 instead of $4.99).
        val priceJson = """{ "amount_micros": 4990000, "currency_code": "USD" }"""
        val price = json.decodeFromString(Price.serializer(), priceJson)
        assertThat(price.amountCents).isEqualTo(499)
        // Round-trip: encode multiplies back to micros.
        val encoded = json.encodeToString(Price.serializer(), price)
        assertThat(encoded).contains("\"amount_micros\":4990000")
    }

    @Test fun decode_unknownFields_ignored() {
        val with = fixture.replace("\"id\":", "\"future_field\": true, \"id\":")
        val p = json.decodeFromString(Product.serializer(), with)
        assertThat(p.id).isEqualTo("pro_monthly")
    }

    @Test fun productCatalog_decodes() {
        val catalogJson = """{ "products": [$fixture] }"""
        val cat = json.decodeFromString(ProductCatalog.serializer(), catalogJson)
        assertThat(cat.products).hasSize(1)
        assertThat(cat.products[0].id).isEqualTo("pro_monthly")
    }
}
