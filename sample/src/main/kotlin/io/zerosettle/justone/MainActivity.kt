package io.zerosettle.justone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import io.zerosettle.justone.app.JustOneNav
import io.zerosettle.justone.app.JustOneTheme
import io.zerosettle.justone.app.Routes
import io.zerosettle.justone.data.UserPrefs
import io.zerosettle.justone.sdk.OfferHolder
import com.zerosettle.sdk.Identity
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Sentinel used to distinguish "DataStore not yet loaded" from a real `null` (never dismissed). */
private const val PAYWALL_SENTINEL_LOADING = Long.MIN_VALUE

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCheckoutCallback(intent)
        setContent {
            JustOneTheme {
                JustOneRoot()
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

@Composable
private fun JustOneRoot() {
    val ctx = LocalContext.current
    val prefs = remember { UserPrefs(ctx) }
    val nav = rememberNavController()

    // Resolve the start destination exactly once from the FIRST DataStore emission.
    // `identity` is an async Flow; reading it via collectAsState would hand NavHost a
    // transient `null` and lock its startDestination onto CREATE_USER for a returning
    // user. A brief cold-start blank is the accepted tradeoff (see plan open questions).
    var startDestination by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val id = prefs.identity.first()
        startDestination = if (id == null) Routes.CREATE_USER else Routes.HOME
    }

    val dest = startDestination
    if (dest == null) {
        // brief cold-start blank — acceptable per the plan
        Surface(modifier = Modifier.fillMaxSize()) {}
        return
    }

    // Cold-launch identity replay: if a persisted user exists but the SDK isn't
    // bootstrapped yet, replay identify (3 retries on transient failure).
    LaunchedEffect(Unit) {
        val id = prefs.identity.first() ?: return@LaunchedEffect
        if (ZeroSettle.isBootstrapped.value) return@LaunchedEffect
        repeat(3) {
            val r = ZeroSettle.identify(Identity.User(id.userId, id.displayName, id.email))
            if (r.isSuccess) return@LaunchedEffect
        }
    }

    // Reset the shared OfferManager whenever the SDK user id clears.
    val userId by ZeroSettle.currentUserId.collectAsState()
    LaunchedEffect(userId) { if (userId == null) OfferHolder.reset() }

    JustOneNav(nav, startDestination = dest)

    // Launch-paywall trigger (spec §5): fires once per session on cold launch.
    // A non-premium user who has never dismissed the paywall is navigated to LAUNCH_PAYWALL.
    val isBootstrapped by ZeroSettle.isBootstrapped.collectAsState()
    val entitlements by ZeroSettle.entitlements.collectAsState()
    val identity by prefs.identity.collectAsState(initial = null)
    val dismissedAt by prefs.paywallDismissedAt.collectAsState(initial = PAYWALL_SENTINEL_LOADING)
    var paywallEvaluated by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isBootstrapped, entitlements, dismissedAt, identity) {
        if (paywallEvaluated) return@LaunchedEffect
        if (!isBootstrapped) return@LaunchedEffect
        if (dismissedAt == PAYWALL_SENTINEL_LOADING) return@LaunchedEffect   // prefs not loaded yet
        if (identity == null) return@LaunchedEffect                          // onboarding tree — no paywall
        val isPremium = entitlements.any { it.isActive && it.productType != "consumable" }
        paywallEvaluated = true
        if (!isPremium && dismissedAt == null) {
            nav.navigate(Routes.LAUNCH_PAYWALL) { launchSingleTop = true }
        }
    }
}
