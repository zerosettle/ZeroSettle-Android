package io.zerosettle.justone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import io.zerosettle.justone.legacy.LegacyDebugRoot
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch

class SampleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCheckoutCallback(intent)

        setContent {
            LegacyDebugRoot()
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
