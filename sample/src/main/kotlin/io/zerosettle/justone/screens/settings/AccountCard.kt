package io.zerosettle.justone.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import io.zerosettle.justone.BuildConfig
import io.zerosettle.justone.data.UserPrefs
import io.zerosettle.justone.sdk.OfferHolder
import kotlinx.coroutines.launch

@Composable
fun AccountCard(
    onSignedOut: () -> Unit,
    onVersionTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val userId by ZeroSettle.currentUserId.collectAsState()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Account",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "User ID",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = userId ?: "—",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Tapping the version line is a hidden developer entry point (wired in a later task).
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onVersionTap),
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { scope.launch { ZeroSettle.restoreEntitlements() } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore purchases")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    ZeroSettle.logout()
                    scope.launch { UserPrefs(ctx).clearIdentity() }
                    OfferHolder.reset()
                    onSignedOut()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign out")
            }
        }
    }
}
