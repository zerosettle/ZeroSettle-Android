package com.zerosettle.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.offers.BannerVisibility
import com.zerosettle.sdk.offers.ImpressionDedupe

/** Report an on-screen offer impression when this composable is >=50% visible,
 *  once per session. Auto-resolves the active offer; pass ids to override. */
public fun Modifier.offerImpression(
    productId: String? = null,
    variantId: Int? = null,
    flowType: String? = null,
): Modifier = composed {
    val dedupe = remember { ImpressionDedupe() }
    val rootView = LocalView.current
    onGloballyPositioned { coords ->
        val b = coords.boundsInWindow()
        val card = BannerVisibility.Rect(b.left, b.top, b.right, b.bottom)
        val vp = BannerVisibility.Rect(0f, 0f, rootView.width.toFloat(), rootView.height.toFloat())
        if (!BannerVisibility.isOnScreen(card, vp, 0.5f)) return@onGloballyPositioned
        val resolved = if (productId != null) Triple(productId, variantId, flowType ?: "migration")
            else ZeroSettle.currentOffer.value?.let { Triple(it.productId, it.variantId, it.flowType) }
            ?: return@onGloballyPositioned
        val uid = ZeroSettle.currentUserId.value?.ifEmpty { "anonymous" } ?: "anonymous"
        val key = "$uid:${ZeroSettle.sessionId}:${resolved.second ?: -1}"
        if (!dedupe.shouldReport(key)) return@onGloballyPositioned
        ZeroSettle.reportOfferViewed(resolved.first, resolved.second, resolved.third)
    }
}
