package com.zerosettle.sample.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.models.PendingAction
import com.zerosettle.ui.ZeroSettlePendingActionBanner
import kotlinx.coroutines.launch

@Composable
fun PendingActionsScreen() {
    val actions by ZeroSettle.pendingActions.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pending actions (${actions.size})", style = MaterialTheme.typography.titleLarge)
        if (actions.isEmpty()) Text("(none — backend has no pending prompts for this user)")
        actions.forEach { action ->
            ZeroSettlePendingActionBanner(
                action = action,
                onDeepLink = { url -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                onDismiss = { a ->
                    // MigrationCompletedInfo → tell the backend; ManualPlayCancel → just hides for the session.
                    if (a is PendingAction.MigrationCompletedInfo) {
                        scope.launch { ZeroSettle.dismissPendingAction(a) }
                    }
                },
            )
        }
    }
}
