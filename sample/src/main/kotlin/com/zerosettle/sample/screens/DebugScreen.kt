package com.zerosettle.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch

@Composable
fun DebugScreen(nav: NavController) {
    var token by remember { mutableStateOf("") }
    var queueDepth by remember { mutableIntStateOf(-1) }
    val events = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        token = runCatching { ZeroSettle.recommendedAppAccountToken().toString() }.getOrElse { "n/a (${it.message})" }
        ZeroSettle.events.collect { events.add(0, it.toString()) }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("isConfigured=${ZeroSettle.isConfigured.value} isBootstrapped=${ZeroSettle.isBootstrapped.value}")
        Text("pendingCheckout=${ZeroSettle.pendingCheckout.value}")
        Text("recommendedAppAccountToken=$token")
        Button(onClick = {
            scope.launch { queueDepth = runCatching { ZeroSettle.playSyncQueueDepthForDebug() }.getOrElse { -1 } }
        }) { Text("Refresh sync-queue depth (=$queueDepth)") }
        Button(onClick = { ZeroSettle.logout() }) { Text("Logout (clears identity + queue)") }
        Text("Events:")
        events.take(20).forEach { Text("• $it") }
    }
}
