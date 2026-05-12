package com.zerosettle.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zerosettle.sample.screens.DebugScreen
import com.zerosettle.sample.screens.EntitlementsScreen
import com.zerosettle.sample.screens.OffersScreen
import com.zerosettle.sample.screens.PendingActionsScreen
import com.zerosettle.sample.screens.ProductsScreen
import com.zerosettle.sdk.Identity
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.ui.theme.ZeroSettleTheme
import kotlinx.coroutines.launch

class SampleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            ZeroSettle.identify(
                Identity.User(id = SampleConfig.TEST_USER_ID, name = "Sample User", email = "sample@example.com"),
            )
        }
        handleCheckoutCallback(intent)

        setContent {
            MaterialTheme {
                ZeroSettleTheme {
                    val nav = rememberNavController()
                    Scaffold { padding ->
                        NavHost(nav, startDestination = "products", modifier = Modifier.padding(padding)) {
                            composable("products") { ProductsScreen(nav) }
                            composable("entitlements") { EntitlementsScreen(nav) }
                            composable("offers") { OffersScreen(nav) }
                            composable("pending") { PendingActionsScreen(nav) }
                            composable("debug") { DebugScreen(nav) }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCheckoutCallback(intent)
    }

    private fun handleCheckoutCallback(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (data.startsWith("zerosettle://checkout")) {
            lifecycleScope.launch { ZeroSettle.completeWebCheckout(data) }
        }
    }
}
