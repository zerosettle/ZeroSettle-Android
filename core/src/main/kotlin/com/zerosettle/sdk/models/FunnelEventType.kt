package com.zerosettle.sdk.models

/** Paywall and checkout funnel events for [com.zerosettle.sdk.ZeroSettle.trackEvent]. */
public enum class FunnelEventType(public val wireValue: String) {
    PAYWALL_VIEWED("paywall_viewed"),
    CHECKOUT_STARTED("checkout_started"),
    CHECKOUT_ABANDONED("checkout_abandoned"),
}
