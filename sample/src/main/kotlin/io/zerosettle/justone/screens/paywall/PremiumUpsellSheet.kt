package io.zerosettle.justone.screens.paywall

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumUpsellSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()

    val products by ZeroSettle.products.collectAsState()
    val plans = remember(products) { subscriptionPlans(products) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(plans) {
        if (selectedId == null || plans.none { it.id == selectedId }) {
            selectedId = defaultPlanId(plans)
        }
    }
    val selected = plans.firstOrNull { it.id == selectedId }
    var purchasing by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Go Premium",
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Unlimited habits, streak savers, and more.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (selected != null) {
                CheckoutSheetHeader(
                    product = selected,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                PlanSelector(
                    plans = plans,
                    selectedId = selected.id,
                    onSelect = { selectedId = it },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Single "Buy" button. On Android the purchase is routed
                // through Google's User Choice Billing screen, which presents
                // the Play-vs-web choice — the app must not hand-roll its own
                // payment-method picker.
                Button(
                    onClick = {
                        scope.launch {
                            purchasing = true
                            try {
                                val result = ZeroSettle.purchaseViaPlayBilling(
                                    activity,
                                    selected.id,
                                )
                                if (result.isSuccess) onDismiss()
                            } finally {
                                purchasing = false
                            }
                        }
                    },
                    enabled = !purchasing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (purchasing) "Processing…" else "Buy")
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = "Maybe later",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
