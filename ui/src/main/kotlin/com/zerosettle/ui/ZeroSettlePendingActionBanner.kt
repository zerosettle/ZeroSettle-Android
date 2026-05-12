package com.zerosettle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.models.PendingAction

/**
 * Renders a single [PendingAction]:
 *  - [PendingAction.MigrationCompletedInfo] — one-time info card; "Got it" → [onDismiss]
 *    (the host wires that to `ZeroSettle.dismissPendingAction(action)`).
 *  - [PendingAction.ManualPlayCancel] — actionable card; "Cancel on Google Play" →
 *    [onDeepLink] with the Play subscriptions URL (host opens it via an `ACTION_VIEW`
 *    intent); "Later" → [onDismiss] (just hides the banner — it re-appears on the next
 *    poll until Play sends the cancel RTDN, which is what [onDismiss] should NOT do for
 *    this variant; pass a no-op or a "hide for this session" handler).
 *
 * For a list of actions, render the first one (`ZeroSettle.pendingActions.firstOrNull()`).
 * The dismissal key is [PendingAction.transactionId] (matches the `:core` contract).
 */
@Composable
public fun ZeroSettlePendingActionBanner(
    action: PendingAction,
    onDeepLink: (url: String) -> Unit,
    onDismiss: (action: PendingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(action.userMessage, style = MaterialTheme.typography.bodyMedium)
            when (action) {
                is PendingAction.ManualPlayCancel -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDeepLink(action.deepLink) }) { Text("Cancel on Google Play") }
                    TextButton(onClick = { onDismiss(action) }) { Text("Later") }
                }
                is PendingAction.MigrationCompletedInfo ->
                    Button(onClick = { onDismiss(action) }) { Text("Got it") }
            }
        }
    }
}
