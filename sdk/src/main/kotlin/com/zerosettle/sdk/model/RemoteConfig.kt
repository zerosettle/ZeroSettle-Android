package com.zerosettle.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Geographic jurisdiction for checkout configuration.
 * The SDK detects the user's jurisdiction via TelephonyManager/Locale
 * and applies the matching override (or falls back to the global default).
 */
@Serializable
enum class Jurisdiction {
    @SerialName("us")
    US,

    @SerialName("eu")
    EU,

    @SerialName("row")
    ROW;

    companion object {
        /** Map a country code (ISO 3166-1 alpha-2) to a jurisdiction. */
        fun from(countryCode: String): Jurisdiction {
            val upper = countryCode.uppercase()
            if (upper == "US" || upper == "USA") return US
            if (upper in EU_CODES) return EU
            return ROW
        }

        private val EU_CODES = setOf(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE",
            "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV",
            "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK",
            "SI", "ES", "SE",
        )
    }
}

/**
 * Per-jurisdiction checkout configuration override.
 */
data class JurisdictionCheckoutConfig(
    val sheetType: CheckoutType,
    val isEnabled: Boolean,
)

/**
 * The type of checkout UI to present.
 * Configured remotely via the ZeroSettle dashboard.
 *
 * Android maps iOS "safari_vc" → CUSTOM_TAB and "safari" → EXTERNAL_BROWSER.
 */
@Serializable
enum class CheckoutType {
    @SerialName("webview")
    WEB_VIEW,

    @SerialName("safari_vc")
    CUSTOM_TAB,

    @SerialName("safari")
    EXTERNAL_BROWSER,

    @SerialName("native_pay")
    NATIVE_PAY;

    companion object {
        fun fromWireValue(value: String): CheckoutType? = when (value) {
            "webview" -> WEB_VIEW
            "safari_vc" -> CUSTOM_TAB
            "safari" -> EXTERNAL_BROWSER
            "native_pay" -> NATIVE_PAY
            else -> null
        }
    }
}

/**
 * Configuration for the checkout UI behavior.
 */
data class CheckoutConfig(
    val sheetType: CheckoutType,
    val isEnabled: Boolean,
    val jurisdictions: Map<Jurisdiction, JurisdictionCheckoutConfig> = emptyMap(),
)

/**
 * Data for a migration campaign prompt.
 */
@Serializable
data class MigrationPrompt(
    @SerialName("product_id")
    val productId: String,
    @SerialName("discount_percent")
    val discountPercent: Int,
    val title: String,
    val message: String,
    @SerialName("cta_text")
    val ctaText: String,
)

/**
 * Remote configuration from the ZeroSettle backend.
 */
data class RemoteConfig(
    val checkout: CheckoutConfig,
    val migration: MigrationPrompt?,
)
