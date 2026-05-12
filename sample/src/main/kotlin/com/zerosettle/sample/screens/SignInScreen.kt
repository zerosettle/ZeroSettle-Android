package com.zerosettle.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.zerosettle.sample.OfferHolder
import com.zerosettle.sample.SampleConfig
import com.zerosettle.sdk.Identity
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch

/** Trivial identify screen — mirrors JustOne's LoginView but with a plain user-id text field. */
@Composable
fun SignInScreen(onIdentified: () -> Unit) {
    var userId by remember { mutableStateOf(SampleConfig.TEST_USER_ID) }
    var name by remember { mutableStateOf("Sample User") }
    var email by remember { mutableStateOf("sample@example.com") }
    var status by remember { mutableStateOf("Not identified yet.") }
    val configured by ZeroSettle.isConfigured.collectAsState()
    val bootstrapped by ZeroSettle.isBootstrapped.collectAsState()
    val scope = rememberCoroutineScope()

    fun identify(identity: Identity) {
        status = "Identifying…"
        scope.launch {
            val r = ZeroSettle.identify(identity)
            if (r.isSuccess) {
                OfferHolder.reset() // new identity → fresh offer manager
                status = "Identified. Bootstrapped=${ZeroSettle.isBootstrapped.value}"
                onIdentified()
            } else {
                status = "identify() failed: ${r.exceptionOrNull()?.message}"
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ZeroSettle Sample — Sign in")
        Text("configured=$configured  bootstrapped=$bootstrapped")
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
                OfferHolder.reset()
                status = "Logged out."
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Logout (clear identity)") }
        Text(status)
    }
}
