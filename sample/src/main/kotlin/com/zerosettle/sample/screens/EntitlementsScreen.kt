package com.zerosettle.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavController
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch

@Composable
fun EntitlementsScreen(nav: NavController) {
    val ents by ZeroSettle.entitlements.collectAsState()
    val claims by ZeroSettle.pendingClaims.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { ZeroSettle.restoreEntitlements() }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Entitlements (${ents.size})")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ents) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${e.productId} — ${e.status} — ${e.source}")
                        Text("active=${e.isActive} willRenew=${e.willRenew} expires=${e.expiresAt ?: "n/a"}")
                    }
                }
            }
            items(claims) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Claim available: ${c.productId} (owner ${c.existingOwnerHint})")
                        Button(onClick = { scope.launch { ZeroSettle.transferPlayOwnershipToCurrentUser(c.productId, c.originalTransactionId) } }) { Text("Claim") }
                    }
                }
            }
        }
    }
}
