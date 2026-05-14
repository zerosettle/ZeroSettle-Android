package com.zerosettle.sdk.checkout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How the server wants the web checkout surfaced. Sent in offer / checkout-config
 * payloads. The `:ui` module's `ZeroSettleCheckoutSheet` and `ZeroSettleOfferTip`
 * can map these to richer Compose presentations (inline Compose WebView, bottom
 * sheet) when the adopter takes that dependency.
 *
 * In the headless `:core` module:
 *  - [CUSTOM_TAB] → AndroidX Browser Custom Tab (default safe fallback).
 *  - [BROWSER] → external browser intent.
 *  - [INLINE] and [SHEET] → in-app WebView hosted by
 *    [ZeroSettleWebViewActivity], auto-registered in the SDK manifest so
 *    every adopter (Compose-free Views, Flutter, plain Activities) gets
 *    WebView presentation without per-host wiring. Adopters who want a
 *    bottom-sheet look can still layer the `:ui` `ZeroSettleCheckoutSheet`
 *    on top — that path overrides the activity launch.
 */
@Serializable
public enum class CheckoutPresentation {
    @SerialName("inline") INLINE,
    @SerialName("sheet") SHEET,
    @SerialName("custom_tab") CUSTOM_TAB,
    @SerialName("browser") BROWSER,
}
