package io.zerosettle.justone.screens.paywall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import io.zerosettle.justone.data.UserPrefs
import kotlinx.coroutines.launch

@Composable
fun LaunchPaywallScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Hero / value-prop area
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Unlock unlimited habits",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Build the life you want — track every habit that matters to you. " +
                    "Premium unlocks unlimited habits, streak savers so you never lose " +
                    "your momentum, and detailed insights to keep you on track.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Subscription section
        Column(modifier = Modifier.fillMaxWidth()) {
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

                DualPriceButtons(
                    productId = selected.id,
                    onPurchased = onDone,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Free-version escape hatch
        TextButton(
            onClick = {
                scope.launch {
                    UserPrefs(ctx).setPaywallDismissedAt(System.currentTimeMillis())
                    onDone()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally),
        ) {
            Text(
                text = "Continue with free version",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
