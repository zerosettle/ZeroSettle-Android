package io.zerosettle.justone.screens.paywall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch

@Composable
fun DualPriceButtons(
    productId: String,
    onPurchased: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as android.app.Activity
    val product = ZeroSettle.product(productId)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    if (product == null) {
        Button(
            onClick = {},
            enabled = false,
            modifier = modifier.fillMaxWidth(),
        ) {
            Text("Unavailable")
        }
        return
    }

    val webAvailable = product.webPrice != null
    val playAvailable = product.playStorePrice != null

    when {
        webAvailable && playAvailable -> {
            val webCents = product.webPrice!!.amountCents
            val playCents = product.playStorePrice!!.amountCents
            val webLabel = if (webCents < playCents) {
                val savings = (playCents - webCents) * 100 / playCents
                "Pay on web — save $savings%"
            } else {
                "Pay on web"
            }

            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val r = ZeroSettle.purchase(activity, productId)
                                if (r.isSuccess) onPurchased()
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(webLabel)
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val r = ZeroSettle.purchaseViaPlayBilling(activity, productId)
                                if (r.isSuccess) onPurchased()
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Google Play")
                }
            }
        }

        webAvailable -> {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val r = ZeroSettle.purchase(activity, productId)
                            if (r.isSuccess) onPurchased()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Pay on web")
            }
        }

        playAvailable -> {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val r = ZeroSettle.purchaseViaPlayBilling(activity, productId)
                            if (r.isSuccess) onPurchased()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Google Play")
            }
        }

        else -> {
            Button(
                onClick = {},
                enabled = false,
                modifier = modifier.fillMaxWidth(),
            ) {
                Text("Unavailable")
            }
        }
    }
}
