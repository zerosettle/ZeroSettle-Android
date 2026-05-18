package io.zerosettle.justone.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.zerosettle.justone.sdk.OfferHolder
import io.zerosettle.justone.SampleConfig
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun maskKey(k: String): String =
    if (k.length <= 8) k else "${k.take(11)}…${k.takeLast(4)}"

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Rich debug menu — mirrors JustOne's DebugSettingsView. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ts = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    var token by remember { mutableStateOf("(tap to load)") }
    var queueDepth by remember { mutableIntStateOf(-1) }
    var txnHistory by remember { mutableStateOf<String?>(null) }
    val events = remember { mutableStateListOf<String>() }

    val configured by ZeroSettle.isConfigured.collectAsState()
    val bootstrapped by ZeroSettle.isBootstrapped.collectAsState()
    val pendingCheckout by ZeroSettle.pendingCheckout.collectAsState()
    val products by ZeroSettle.products.collectAsState()
    val entitlements by ZeroSettle.entitlements.collectAsState()

    LaunchedEffect(Unit) {
        ZeroSettle.events.collect { e -> events.add(0, "[${ts.format(Date())}] $e") }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Debug", style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Config", style = MaterialTheme.typography.titleSmall)
                Text("publishableKey = ${maskKey(SampleConfig.PUBLISHABLE_KEY)}")
                Text("env = ${SampleConfig.loadEnv(ctx).label}")
                Text("baseUrl = ${SampleConfig.effectiveBaseUrl(ctx)}")
                Text("(change env on the Sign-in screen)")
                Text("sdkVersion = ${ZeroSettle.sdkVersion}")
                Text("configured=$configured  bootstrapped=$bootstrapped  pendingCheckout=$pendingCheckout")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("recommendedAppAccountToken", style = MaterialTheme.typography.titleSmall)
                Text(token, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        token = runCatching { ZeroSettle.recommendedAppAccountToken().toString() }
                            .getOrElse { "n/a (${it.message})" }
                    }) { Text("Load token") }
                    OutlinedButton(onClick = { copyToClipboard(ctx, "appAccountToken", token) }) { Text("Copy") }
                }
                Text("playSyncQueueDepth = ${if (queueDepth < 0) "?" else queueDepth}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    scope.launch { queueDepth = runCatching { ZeroSettle.playSyncQueueDepthForDebug() }.getOrElse { -1 } }
                }) { Text("Refresh queue depth") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Actions", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { OfferHolder.getOrNull()?.evaluate() } }) { Text("Re-evaluate offer") }
                    OutlinedButton(onClick = { scope.launch { ZeroSettle.restoreEntitlements() } }) { Text("Force restore entitlements") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val r = ZeroSettle.fetchTransactionHistory()
                            txnHistory = if (r.isSuccess) {
                                val list = r.getOrNull().orEmpty()
                                buildString {
                                    append(list.size).append(" txns\n")
                                    list.forEach { t ->
                                        append("• ").append(t.id)
                                            .append(" ").append(t.productId)
                                            .append(" ").append(t.status.wire)
                                            .append(" ").append(t.source.wire)
                                            .append(" ").append(t.amountCents ?: "—")
                                            .append(" ").append(t.currency ?: "—")
                                            .append("\n")
                                    }
                                }
                            } else {
                                "failed: ${r.exceptionOrNull()?.message}"
                            }
                        }
                    }) { Text("Fetch txn history") }
                    OutlinedButton(onClick = { scope.launch { ZeroSettle.fetchProducts() } }) { Text("Fetch products") }
                    OutlinedButton(onClick = { events.clear() }) { Text("Clear event log") }
                    Button(onClick = { ZeroSettle.logout(); OfferHolder.reset() }) { Text("Logout") }
                }
                txnHistory?.let {
                    Text("txn history (raw, truncated):", style = MaterialTheme.typography.bodySmall)
                    Text(it.take(2000), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Products (${products.size})", style = MaterialTheme.typography.titleSmall)
                products.forEach { Text("• ${it.id} (${it.type}) web=${it.webPrice?.amountCents ?: "—"} play=${it.playProductId ?: "—"}", style = MaterialTheme.typography.labelSmall) }
                Text("Entitlements (${entitlements.size})", style = MaterialTheme.typography.titleSmall)
                entitlements.forEach { Text("• ${it.productId} ${it.source}/${it.status} active=${it.isActive}", style = MaterialTheme.typography.labelSmall) }
            }
        }

        Text("Event log (${events.size})", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(events) { Text("• $it", style = MaterialTheme.typography.labelSmall) }
        }
    }
}
