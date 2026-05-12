package com.zerosettle.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch

@Composable
fun EntitlementsScreen() {
    val ents by ZeroSettle.entitlements.collectAsState()
    val claims by ZeroSettle.pendingClaims.collectAsState()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { ZeroSettle.restoreEntitlements() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Entitlements (${ents.size})", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = {
            scope.launch {
                val r = ZeroSettle.restoreEntitlements()
                status = if (r.isSuccess) "restored ${r.getOrNull()?.size ?: 0}" else "restore failed: ${r.exceptionOrNull()?.message}"
            }
        }) { Text("Refresh") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ents) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(e.productId, style = MaterialTheme.typography.titleSmall)
                        Text("source=${e.source}  status=${e.status} (${e.statusRaw})  active=${e.isActive}")
                        Text("willRenew=${e.willRenew}  isTrial=${e.isTrial}  trialEnds=${e.trialEndsAt ?: "n/a"}")
                        Text("purchased=${e.purchasedAt ?: "n/a"}  expires=${e.expiresAt ?: "n/a"}")
                        if (e.pausedAt != null) Text("paused=${e.pausedAt} resumes=${e.pauseResumesAt ?: "n/a"}")
                        if (e.subscriptionGroupId != null) Text("group=${e.subscriptionGroupId}")
                    }
                }
            }
            if (claims.isNotEmpty()) {
                item { Text("Pending claims (${claims.size})", style = MaterialTheme.typography.titleMedium) }
            }
            items(claims) { c ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Claimable: ${c.productId}", style = MaterialTheme.typography.titleSmall)
                        Text("owned by: ${c.existingOwnerHint}  (origTxn=${c.originalTransactionId})", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            scope.launch {
                                val r = ZeroSettle.transferPlayOwnershipToCurrentUser(c.productId, c.originalTransactionId)
                                status = if (r.isSuccess) "claimed ${c.productId}" else "claim failed: ${r.exceptionOrNull()?.message}"
                                ZeroSettle.restoreEntitlements()
                            }
                        }) { Text("Claim") }
                    }
                }
            }
        }
    }
}
