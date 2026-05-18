package io.zerosettle.justone.screens.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.models.CancelFlow
import com.zerosettle.ui.ZeroSettleCancelFlow
import kotlinx.coroutines.launch

/** Developer tool: server-driven retention / cancel flow test harness — mirrors JustOne's CancelFlowView. */
@Composable
fun CancelDebugScreen() {
    val entitlements by ZeroSettle.entitlements.collectAsState()
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<CancelFlow.Config?>(null) }
    var status by remember { mutableStateOf("") }

    // Best-effort target: first active auto-renewable / subscription entitlement.
    val targetProductId = entitlements.firstOrNull { it.isActive }?.productId

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cancel flow", style = MaterialTheme.typography.titleLarge)
        Text("target subscription: ${targetProductId ?: "(no active entitlement)"}", style = MaterialTheme.typography.bodySmall)

        Button(onClick = {
            scope.launch {
                val r = ZeroSettle.fetchCancelFlowConfig()
                if (r.isSuccess) { config = r.getOrNull(); status = "config loaded" }
                else status = "fetchCancelFlowConfig failed: ${r.exceptionOrNull()?.message}"
            }
        }) { Text("Start cancel flow") }

        config?.let { cfg ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("config: questions=${cfg.questions.size}  saveOffer=${cfg.saveOffer?.productId ?: "—"} (${cfg.saveOffer?.savingsPercent ?: 0}%)  pauseDays=${cfg.pauseOptionsDays}")
                }
            }
            ZeroSettleCancelFlow(
                config = cfg,
                onResult = { result ->
                    scope.launch {
                        status = when (result) {
                            is CancelFlow.Result.SaveOfferAccepted -> {
                                val r = ZeroSettle.acceptSaveOffer(result.productId)
                                if (r.isSuccess) "save offer accepted → ${r.getOrNull()?.productId}" else "acceptSaveOffer failed: ${r.exceptionOrNull()?.message}"
                            }
                            is CancelFlow.Result.Paused -> {
                                if (targetProductId == null) "no target to pause" else {
                                    val r = ZeroSettle.pauseSubscription(targetProductId, cfg.pauseOptionsDays.firstOrNull())
                                    if (r.isSuccess) "paused (resumes ${r.getOrNull() ?: "?"})" else "pause failed: ${r.exceptionOrNull()?.message}"
                                }
                            }
                            CancelFlow.Result.Cancelled -> {
                                if (targetProductId == null) "no target to cancel" else {
                                    val r = ZeroSettle.cancelSubscription(targetProductId, immediate = false)
                                    if (r.isSuccess) "cancelled $targetProductId" else "cancel failed: ${r.exceptionOrNull()?.message}"
                                }
                            }
                            CancelFlow.Result.Dismissed -> "dismissed cancel flow"
                        }
                        ZeroSettle.restoreEntitlements()
                        config = null
                    }
                },
            )
        }

        Text("Raw subscription controls (target=${targetProductId ?: "—"}):", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = targetProductId != null, onClick = {
                scope.launch {
                    val r = ZeroSettle.cancelSubscription(targetProductId!!, immediate = false)
                    status = if (r.isSuccess) "cancelled" else "cancel failed: ${r.exceptionOrNull()?.message}"
                    ZeroSettle.restoreEntitlements()
                }
            }) { Text("Cancel sub") }
            OutlinedButton(enabled = targetProductId != null, onClick = {
                scope.launch {
                    val r = ZeroSettle.pauseSubscription(targetProductId!!, 30)
                    status = if (r.isSuccess) "paused (resumes ${r.getOrNull() ?: "?"})" else "pause failed: ${r.exceptionOrNull()?.message}"
                    ZeroSettle.restoreEntitlements()
                }
            }) { Text("Pause 30d") }
            OutlinedButton(enabled = targetProductId != null, onClick = {
                scope.launch {
                    val r = ZeroSettle.resumeSubscription(targetProductId!!)
                    status = if (r.isSuccess) "resumed" else "resume failed: ${r.exceptionOrNull()?.message}"
                    ZeroSettle.restoreEntitlements()
                }
            }) { Text("Resume") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
