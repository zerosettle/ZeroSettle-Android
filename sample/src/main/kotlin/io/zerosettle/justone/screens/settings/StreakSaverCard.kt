package io.zerosettle.justone.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle

@Composable
fun StreakSaverCard(
    onBuyMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entitlements by ZeroSettle.entitlements.collectAsState()
    val owned = entitlements.count { it.isActive && it.productType == "consumable" }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Streak Savers",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "$owned owned",
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Use a Streak Saver to protect your streak when you miss a day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onBuyMore,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Buy more")
            }
        }
    }
}
