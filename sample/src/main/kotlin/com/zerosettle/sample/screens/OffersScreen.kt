package com.zerosettle.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.ui.ZeroSettleOfferTip

@Composable
fun OffersScreen(nav: NavController) {
    val manager = remember { ZeroSettle.offerManager() }
    LaunchedEffect(manager) { manager.evaluate() }
    val state by manager.state.collectAsState()

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("OfferManager state: $state")
        // The drop-in UI component — handles all bookkeeping internally.
        ZeroSettleOfferTip(offerManager = manager)
    }
}
