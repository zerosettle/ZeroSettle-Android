package com.zerosettle.sdk.offers

/** Pure vertical >=N% visibility math for the offer banner. */
public object BannerVisibility {
    public data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height: Float get() = bottom - top
    }
    public fun visibleFraction(card: Rect, viewport: Rect): Float {
        if (card.height <= 0f) return 0f
        val top = maxOf(card.top, viewport.top)
        val bottom = minOf(card.bottom, viewport.bottom)
        val visible = maxOf(0f, bottom - top)
        return minOf(1f, visible / card.height)
    }
    public fun isOnScreen(card: Rect, viewport: Rect, threshold: Float = 0.5f): Boolean =
        visibleFraction(card, viewport) >= threshold
}
