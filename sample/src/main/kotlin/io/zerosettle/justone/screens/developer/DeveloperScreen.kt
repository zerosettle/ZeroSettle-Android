package io.zerosettle.justone.screens.developer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val entries = listOf(
    "Environment",
    "Entitlements",
    "Offers",
    "Pending actions",
    "Cancel flow (debug)",
    "Upgrade offer",
    "Debug",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(onBack: () -> Unit) {
    var selected by remember { mutableStateOf<Int?>(null) }

    if (selected == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Developer") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                itemsIndexed(entries) { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = index }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (index < entries.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    } else {
        val title = entries[selected!!]
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { selected = null }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            val contentModifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)

            Box(modifier = contentModifier) {
                when (selected) {
                    0 -> EnvSwitcherScreen(onIdentified = {})
                    1 -> EntitlementsScreen()
                    2 -> OffersScreen()
                    3 -> PendingActionsScreen()
                    4 -> CancelDebugScreen()
                    5 -> UpgradeOfferScreen()
                    6 -> DebugScreen()
                }
            }
        }
    }
}
