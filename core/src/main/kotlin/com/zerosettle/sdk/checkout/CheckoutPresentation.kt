package com.zerosettle.sdk.checkout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How the server wants the web checkout surfaced. Sent in offer / checkout-config
 * payloads. The `:ui` module's `ZeroSettleCheckoutSheet` and `ZeroSettleOfferTip`
 * map these to: an inline Compose WebView, a bottom-sheet Compose WebView, an
 * AndroidX Browser Custom Tab, or an external browser intent respectively.
 *
 * The headless core can only realise [CUSTOM_TAB] and [BROWSER] (no Compose dep);
 * [INLINE] / [SHEET] need the UI artifact. When the headless [WebCheckoutFlow] is
 * asked to present an [INLINE] / [SHEET] offer it falls back to [CUSTOM_TAB].
 */
@Serializable
public enum class CheckoutPresentation {
    @SerialName("inline") INLINE,
    @SerialName("sheet") SHEET,
    @SerialName("custom_tab") CUSTOM_TAB,
    @SerialName("browser") BROWSER,
}
