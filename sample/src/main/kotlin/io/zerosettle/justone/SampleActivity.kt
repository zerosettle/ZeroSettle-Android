package io.zerosettle.justone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.zerosettle.justone.screens.CancelFlowScreen
import io.zerosettle.justone.screens.DebugScreen
import io.zerosettle.justone.screens.EntitlementsScreen
import io.zerosettle.justone.screens.HomeScreen
import io.zerosettle.justone.screens.OffersScreen
import io.zerosettle.justone.screens.PendingActionsScreen
import io.zerosettle.justone.screens.SignInScreen
import io.zerosettle.justone.screens.UpgradeOfferScreen
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.ui.theme.ZeroSettleTheme
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext

class SampleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCheckoutCallback(intent)

        setContent {
            MaterialTheme {
                ZeroSettleTheme {
                    val ctx = LocalContext.current
                    val nav = rememberNavController()
                    val bootstrapped by ZeroSettle.isBootstrapped.collectAsState()
                    val configured by ZeroSettle.isConfigured.collectAsState()
                    val backStack by nav.currentBackStackEntryAsState()
                    val currentRoute = backStack?.destination?.route
                    val showBottomBar = bootstrapped && currentRoute != null && currentRoute != Routes.SIGN_IN

                    // Cold-start identity replay. Mirrors the JustOne iOS
                    // sample: if the user previously identified, route them
                    // straight to HOME — don't make them re-pick their
                    // account every launch. Persisted identity lives in
                    // SampleConfig (SharedPreferences); cleared on explicit
                    // logout / env switch.
                    //
                    // Guard with `bootstrapped` so we don't replay over an
                    // already-active identity (e.g. when the activity
                    // recreates after a config change with a live SDK).
                    LaunchedEffect(configured) {
                        if (!configured || bootstrapped) return@LaunchedEffect
                        val saved = SampleConfig.loadIdentity(ctx) ?: return@LaunchedEffect
                        val r = ZeroSettle.identify(saved)
                        if (r.isFailure) {
                            // Stale or invalid identity — drop it and fall
                            // through to SignInScreen so the user can pick
                            // a fresh one.
                            SampleConfig.clearIdentity(ctx)
                        }
                    }

                    // Once bootstrapped, jump past SIGN_IN. Covers both the
                    // cold-start replay path above and the manual-sign-in
                    // path from SignInScreen (which already calls
                    // onIdentified → nav.navigate(HOME)).
                    LaunchedEffect(bootstrapped) {
                        if (bootstrapped && currentRoute == Routes.SIGN_IN) {
                            nav.navigate(Routes.HOME) {
                                popUpTo(Routes.SIGN_IN) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    // If identity is dropped (e.g. logout from the Debug screen), return to sign-in.
                    LaunchedEffect(bootstrapped, currentRoute) {
                        if (!bootstrapped && currentRoute != null && currentRoute != Routes.SIGN_IN) {
                            nav.navigate(Routes.SIGN_IN) {
                                popUpTo(0) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    Scaffold(
                        bottomBar = {
                            if (showBottomBar) {
                                NavigationBar {
                                    BOTTOM_TABS.forEach { tab ->
                                        NavigationBarItem(
                                            selected = currentRoute == tab.route,
                                            onClick = {
                                                if (currentRoute != tab.route) {
                                                    nav.navigate(tab.route) {
                                                        popUpTo(Routes.HOME) { saveState = true }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            },
                                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                                            label = { Text(tab.label) },
                                        )
                                    }
                                }
                            }
                        },
                    ) { padding ->
                        NavHost(
                            nav,
                            startDestination = Routes.SIGN_IN,
                            modifier = Modifier.padding(padding),
                        ) {
                            composable(Routes.SIGN_IN) {
                                SignInScreen(onIdentified = {
                                    nav.navigate(Routes.HOME) {
                                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                })
                            }
                            composable(Routes.HOME) { HomeScreen() }
                            composable(Routes.ENTITLEMENTS) { EntitlementsScreen() }
                            composable(Routes.OFFERS) { OffersScreen() }
                            composable(Routes.PENDING) { PendingActionsScreen() }
                            composable(Routes.CANCEL) { CancelFlowScreen() }
                            composable(Routes.UPGRADE) { UpgradeOfferScreen() }
                            composable(Routes.DEBUG) { DebugScreen() }
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

    /** Receives the Stripe web-checkout return redirect (`zerosettle://checkout/return?status=...`). */
    private fun handleCheckoutCallback(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (data.startsWith("zerosettle://checkout")) {
            lifecycleScope.launch { ZeroSettle.completeWebCheckout(data) }
        }
    }
}
