package com.zerosettle.sample.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.ui.ZeroSettlePendingActionBanner
import kotlinx.coroutines.launch

@Composable
fun PendingActionsScreen(nav: NavController) {
    val actions by ZeroSettle.pendingActions.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pending actions (${actions.size})")
        actions.firstOrNull()?.let { action ->
            ZeroSettlePendingActionBanner(
                action = action,
                onDeepLink = { url -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                onDismiss = { a -> scope.launch { ZeroSettle.dismissPendingAction(a) } },
            )
        }
    }
}
