package com.zerosettle.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerosettle.sample.OfferHolder
import com.zerosettle.sample.SampleConfig
import com.zerosettle.sample.configureSdk
import com.zerosettle.sdk.Identity
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch

/**
 * Identify screen — pick a backend env (production / staging / local emulator /
 * custom), then identify. Mirrors JustOne's LoginView. The env choice is persisted
 * (SharedPreferences) and reused on next launch.
 */
@Composable
fun SignInScreen(onIdentified: () -> Unit) {
    val ctx = LocalContext.current
    var userId by remember { mutableStateOf(SampleConfig.TEST_USER_ID) }
    var name by remember { mutableStateOf("Sample User") }
    var email by remember { mutableStateOf("sample@example.com") }
    var status by remember { mutableStateOf("Not identified yet.") }

    var env by remember { mutableStateOf(SampleConfig.loadEnv(ctx)) }
    var customUrl by remember { mutableStateOf(SampleConfig.loadCustomUrl(ctx)) }
    var envMenuOpen by remember { mutableStateOf(false) }
    var effectiveUrl by remember { mutableStateOf(SampleConfig.effectiveBaseUrl(ctx)) }

    val configured by ZeroSettle.isConfigured.collectAsState()
    val bootstrapped by ZeroSettle.isBootstrapped.collectAsState()
    val scope = rememberCoroutineScope()

    fun applyEnv(newEnv: SampleConfig.Env, newCustomUrl: String = customUrl) {
        env = newEnv
        customUrl = newCustomUrl
        SampleConfig.saveEnv(ctx, newEnv)
        if (newEnv == SampleConfig.Env.CUSTOM) SampleConfig.saveCustomUrl(ctx, newCustomUrl)
        // Re-point the SDK at the new backend. Drop any prior identity so the user
        // re-identifies against the new env (the buttons below do that). Clear the
        // persisted identity too — an account that exists on staging may not exist
        // on production, so replaying it next launch would 404.
        ZeroSettle.logout()
        SampleConfig.clearIdentity(ctx)
        OfferHolder.reset()
        configureSdk(ctx)
        effectiveUrl = SampleConfig.effectiveBaseUrl(ctx)
        status = "Backend → ${newEnv.label}\n$effectiveUrl\n(re-identify below)"
    }

    fun identify(identity: Identity) {
        status = "Identifying against $effectiveUrl …"
        scope.launch {
            val r = ZeroSettle.identify(identity)
            if (r.isSuccess) {
                // Persist the choice so the user isn't re-prompted on next
                // cold start — replayed by SampleActivity's LaunchedEffect.
                SampleConfig.saveIdentity(ctx, identity)
                OfferHolder.reset()
                status = "Identified ($effectiveUrl). bootstrapped=${ZeroSettle.isBootstrapped.value}"
                onIdentified()
            } else {
                status = "identify() failed against $effectiveUrl:\n${r.exceptionOrNull()?.message}"
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ZeroSettle Sample — Sign in")
        Text("configured=$configured  bootstrapped=$bootstrapped")

        HorizontalDivider()
        Text("Backend environment")
        Box {
            OutlinedButton(onClick = { envMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Environment: ${env.label}")
            }
            DropdownMenu(expanded = envMenuOpen, onDismissRequest = { envMenuOpen = false }) {
                SampleConfig.Env.entries.forEach { e ->
                    DropdownMenuItem(
                        text = { Text(e.label) },
                        onClick = {
                            envMenuOpen = false
                            if (e == SampleConfig.Env.CUSTOM) {
                                env = e
                                SampleConfig.saveEnv(ctx, e)
                                status = "Enter a custom base URL, then tap “Use custom URL”."
                            } else {
                                applyEnv(e)
                            }
                        },
                    )
                }
            }
        }
        if (env == SampleConfig.Env.CUSTOM) {
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text("Custom base URL (https://abc.ngrok.io or http://192.168.x.x:8000)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { applyEnv(SampleConfig.Env.CUSTOM, customUrl.trim()) },
                enabled = customUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use custom URL") }
        }
        Text("→ $effectiveUrl", overflow = TextOverflow.Ellipsis, maxLines = 2)

        HorizontalDivider()
        OutlinedTextField(userId, { userId = it }, label = { Text("User ID") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                identify(Identity.User(id = userId.trim(), name = name.ifBlank { null }, email = email.ifBlank { null }))
            },
            enabled = userId.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Identify as User") }
        OutlinedButton(onClick = { identify(Identity.Anonymous) }, modifier = Modifier.fillMaxWidth()) {
            Text("Identify as Anonymous")
        }
        TextButton(
            onClick = {
                ZeroSettle.logout()
                SampleConfig.clearIdentity(ctx)
                OfferHolder.reset()
                status = "Logged out."
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Logout (clear identity)") }
        Text(status)
    }
}
