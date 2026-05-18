package io.zerosettle.justone.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.Identity
import com.zerosettle.sdk.ZeroSettle
import io.zerosettle.justone.data.UserPrefs
import kotlinx.coroutines.launch

/**
 * Onboarding screen for brand-new users. Collects a display name, synthesizes a
 * stable UUID + fake email, persists via DataStore, and calls [ZeroSettle.identify]
 * (up to 3 retries). On success, [onCreated] is invoked so the nav controller can
 * pop to Home. On failure, an inline error message is shown and the button re-enables.
 */
@Composable
fun CreateUserScreen(onCreated: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Welcome to JustOne",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "What should we call you?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                isError = error != null,
            )

            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    error = null
                    scope.launch {
                        busy = true
                        try {
                            val userId = java.util.UUID.randomUUID().toString()
                            val trimmed = name.trim()
                            val email = "${trimmed.lowercase().substringBefore(' ').ifBlank { "user" }}@gmail.com"

                            // Persist BEFORE identify so a returning user is
                            // recognized even if the next identify is retried on launch.
                            UserPrefs(ctx).saveIdentity(userId, trimmed, email)

                            // Retry identify up to 3 times on transient failure.
                            var success = false
                            repeat(3) {
                                if (success) return@repeat
                                success = ZeroSettle.identify(
                                    Identity.User(id = userId, name = trimmed, email = email)
                                ).isSuccess
                            }

                            if (success) {
                                onCreated()
                            } else {
                                error = "Couldn't connect. Please try again."
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = name.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Create my account")
                }
            }
        }
    }
}
