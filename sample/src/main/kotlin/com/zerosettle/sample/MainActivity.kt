package com.zerosettle.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zerosettle.sample.ui.screens.HomeScreen
import com.zerosettle.sample.ui.screens.SettingsScreen
import com.zerosettle.sample.ui.screens.StoreScreen
import com.zerosettle.sample.ui.theme.ZeroSettleSampleTheme
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch

/**
 * Sample app demonstrating ZeroSettle SDK integration.
 *
 * To test:
 * 1. Replace "zs_pk_test_YOUR_KEY_HERE" with your publishable key
 * 2. Run the app
 * 3. Browse the Store tab to see products
 * 4. Select a product and use Play Store or Web Checkout
 * 5. Check entitlements on the Home tab
 */
class MainActivity : ComponentActivity() {

    private val appState = SampleAppState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Load persisted environment and configure SDK
        appState.initEnvironment(this)
        val env = appState.currentEnvironment

        ZeroSettle.baseUrlOverride = env.baseUrlOverride
        ZeroSettle.configure(
            context = this,
            config = ZeroSettle.Configuration(
                publishableKey = env.publishableKey,
                syncPlayStoreTransactions = true,
            ),
        )
        ZeroSettle.delegate = appState
        appState.activity = this

        setContent {
            ZeroSettleSampleTheme {
                val scope = rememberCoroutineScope()
                // Kick off initial bootstrap (products + entitlements)
                remember {
                    scope.launch { appState.bootstrap() }
                    true
                }

                SampleApp(appState = appState)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { uri ->
            appState.statusMessage = "Deep link received: $uri"
            ZeroSettle.handleDeepLink(uri)
        }
    }

    override fun onResume() {
        super.onResume()
        ZeroSettle.onResume()
    }
}

@Composable
fun SampleApp(appState: SampleAppState) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.ShoppingCart, contentDescription = "Store") },
                    label = { Text("Store") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                )
            }
        },
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            when (selectedTab) {
                0 -> HomeScreen(
                    appState = appState,
                    onNavigateToStore = { selectedTab = 1 },
                )
                1 -> StoreScreen(appState = appState)
                2 -> SettingsScreen(appState = appState)
            }
        }
    }
}
