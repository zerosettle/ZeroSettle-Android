package com.zerosettle.sample.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zerosettle.sample.IAPEnvironment
import com.zerosettle.sample.SampleAppState
import com.zerosettle.sample.ui.theme.Green
import com.zerosettle.sample.ui.theme.Indigo
import com.zerosettle.sample.ui.theme.Orange
import com.zerosettle.sample.ui.theme.Purple
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appState: SampleAppState,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var customUserId by remember { mutableStateOf("") }
    var isRestoring by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Environment Picker Section
            SettingsSection(title = "IAP Environment") {
                var isSwitching by remember { mutableStateOf(false) }

                IAPEnvironment.entries.forEach { env ->
                    val isSelected = appState.currentEnvironment == env
                    val borderColor by animateColorAsState(
                        if (isSelected) Indigo else MaterialTheme.colorScheme.outlineVariant,
                        label = "border",
                    )
                    val bgColor by animateColorAsState(
                        if (isSelected) Indigo.copy(alpha = 0.08f) else Color.Transparent,
                        label = "bg",
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                            .background(bgColor)
                            .clickable(enabled = !isSwitching && !isSelected) {
                                scope.launch {
                                    isSwitching = true
                                    try {
                                        appState.switchEnvironment(env)
                                        snackbarHostState.showSnackbar("Switched to ${env.displayName}")
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Error: ${e.message}")
                                    } finally {
                                        isSwitching = false
                                    }
                                }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Selection indicator
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .border(
                                    2.dp,
                                    if (isSelected) Indigo else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                )
                                .then(
                                    if (isSelected) Modifier.background(Indigo, CircleShape)
                                    else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color.White,
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = env.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                text = env.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (isSwitching && appState.currentEnvironment != env) {
                            // Show spinner only on the one being switched to
                        }
                    }
                }

                // Show current key + URL
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Key: ${appState.currentEnvironment.publishableKey.take(25)}...",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "URL: ${appState.currentEnvironment.baseUrlOverride ?: "api.zerosettle.io/v1"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = "Select the environment for in-app purchases. Changes take effect immediately.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // User ID Section
            SettingsSection(title = "User ID Testing") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Current User ID",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = appState.userId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = customUserId,
                    onValueChange = { customUserId = it },
                    label = { Text("Custom User ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (customUserId.isNotBlank()) {
                                appState.userId = customUserId
                                customUserId = ""
                                scope.launch { appState.fetchProducts() }
                            }
                        },
                    ) { Text("Set") }

                    OutlinedButton(
                        onClick = {
                            appState.userId = "user_storefront_demo"
                            scope.launch { appState.fetchProducts() }
                        },
                    ) { Text("Demo User") }

                    OutlinedButton(
                        onClick = {
                            appState.userId = "user_${System.currentTimeMillis()}"
                            scope.launch { appState.fetchProducts() }
                        },
                    ) { Text("Random") }
                }

                Text(
                    text = "Switch between user IDs to test purchase persistence across users.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // SDK Info Section
            SettingsSection(title = "SDK Info") {
                InfoRow("Checkout Type", appState.checkoutTypeName)
                InfoRow("Jurisdiction", appState.jurisdictionName ?: "Unknown")
                InfoRow("Web Checkout", if (appState.isWebCheckoutEnabled) "Enabled" else "Disabled")
                InfoRow("Products Loaded", "${appState.products.size}")
                InfoRow("Active Entitlements", "${appState.entitlements.count { it.isActive }}")
            }

            // Actions Section
            SettingsSection(title = "Actions") {
                ActionButton(
                    text = "Fetch Products",
                    icon = Icons.Filled.Refresh,
                    color = Indigo,
                    onClick = {
                        scope.launch {
                            appState.fetchProducts()
                            snackbarHostState.showSnackbar("Fetched ${appState.products.size} products")
                        }
                    },
                )

                ActionButton(
                    text = "Bootstrap (Products + Entitlements)",
                    icon = Icons.Filled.Refresh,
                    color = Purple,
                    onClick = {
                        scope.launch {
                            try {
                                appState.bootstrap()
                                snackbarHostState.showSnackbar("Bootstrap complete: ${appState.products.size} products")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Error: ${e.message}")
                            }
                        }
                    },
                )

                ActionButton(
                    text = "Restore Entitlements",
                    icon = Icons.Filled.Refresh,
                    color = Green,
                    isLoading = isRestoring,
                    onClick = {
                        scope.launch {
                            isRestoring = true
                            try {
                                appState.restoreEntitlements()
                                snackbarHostState.showSnackbar("Restored ${appState.entitlements.size} entitlements")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Error: ${e.message}")
                            } finally {
                                isRestoring = false
                            }
                        }
                    },
                )

                ActionButton(
                    text = "Open Customer Portal",
                    icon = Icons.Filled.CreditCard,
                    color = Orange,
                    onClick = {
                        scope.launch {
                            try {
                                appState.openCustomerPortal()
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Error: ${e.message}")
                            }
                        }
                    },
                )

                ActionButton(
                    text = "Manage Subscription",
                    icon = Icons.Filled.Settings,
                    color = Orange,
                    onClick = {
                        scope.launch {
                            try {
                                appState.manageSubscription()
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Error: ${e.message}")
                            }
                        }
                    },
                )
            }

            // Error Testing Section
            SettingsSection(title = "Error Scenario Testing") {
                ActionButton(
                    text = "Purchase Invalid Product",
                    icon = Icons.Filled.ErrorOutline,
                    color = Color(0xFFE53935),
                    onClick = {
                        scope.launch {
                            try {
                                appState.purchaseViaWeb("nonexistent_product_12345")
                                lastError = "Unexpected success"
                            } catch (e: Exception) {
                                lastError = "[${e::class.simpleName}] ${e.message}"
                            }
                        }
                    },
                )

                lastError?.let { error ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color.Red.copy(alpha = 0.08f),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(12.dp),
                    ) {
                        Text(
                            text = "Last Error:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE53935),
                        )
                    }
                    OutlinedButton(onClick = { lastError = null }) {
                        Text("Clear Error")
                    }
                }

                Text(
                    text = "Test error handling by triggering known failure scenarios.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    isLoading: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
        ),
        enabled = !isLoading,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
