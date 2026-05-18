package io.zerosettle.justone.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zerosettle.ui.ZeroSettleOfferTip
import io.zerosettle.justone.sdk.OfferHolder

@Composable
fun OfferCardSlot(modifier: Modifier = Modifier) {
    ZeroSettleOfferTip(
        offerManager = OfferHolder.get(),
        modifier = modifier,
    )
}
