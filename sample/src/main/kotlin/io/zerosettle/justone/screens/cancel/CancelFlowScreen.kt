package io.zerosettle.justone.screens.cancel

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.models.CancelFlow
import com.zerosettle.ui.ZeroSettleCancelFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Server-driven cancel / retention flow for the JustOne sample app. Fetches the
 * retention config, hands it to the `:ui` [ZeroSettleCancelFlow] composable, then
 * calls the matching `ZeroSettle.*` method based on the terminal [CancelFlow.Result].
 *
 * `fetchCancelFlowConfig()` has a documented wire-shape divergence — against a live
 * backend its decode can fail and the call returns `Result.failure`. The [loadFailed]
 * branch is the genuine fallback path: it offers a direct "Cancel anyway" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CancelFlowScreen(productId: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<CancelFlow.Config?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var showConfetti by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val r = ZeroSettle.fetchCancelFlowConfig()
        if (r.isSuccess) config = r.getOrNull() else loadFailed = true
    }

    fun handleResult(result: CancelFlow.Result) {
        when (result) {
            is CancelFlow.Result.Cancelled -> scope.launch {
                ZeroSettle.cancelSubscription(productId, immediate = false)
                showConfetti = true
            }
            is CancelFlow.Result.Paused -> scope.launch {
                ZeroSettle.pauseSubscription(productId, config?.pauseOptionsDays?.firstOrNull())
                onDone()
            }
            is CancelFlow.Result.SaveOfferAccepted -> scope.launch {
                ZeroSettle.acceptSaveOffer(result.productId)
                onDone()
            }
            CancelFlow.Result.Dismissed -> onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cancel subscription") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                showConfetti -> ConfettiSuccess(onDone = onDone)

                config == null && !loadFailed -> CircularProgressIndicator()

                loadFailed -> Card(modifier = Modifier.padding(24.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Couldn't load retention options.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "You can still cancel your subscription below.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    ZeroSettle.cancelSubscription(productId, immediate = false)
                                    showConfetti = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel anyway") }
                        TextButton(
                            onClick = onDone,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Keep my subscription") }
                    }
                }

                config != null -> ZeroSettleCancelFlow(
                    config = config!!,
                    onResult = { handleResult(it) },
                )
            }
        }
    }
}

private data class ConfettiPiece(
    val startX: Float,
    val drift: Float,
    val color: Color,
    val rotation: Float,
    val size: Float,
)

private val confettiColors = listOf(
    Color(0xFF6CA358),
    Color(0xFFD97706),
    Color(0xFF3B82F6),
    Color(0xFFEC4899),
    Color(0xFFF59E0B),
)

/**
 * A modest celebratory confetti flourish over a "Subscription cancelled" message.
 * Auto-dismisses the whole flow via [onDone] after a short delay. Mirrors iOS
 * `CancelConfettiView`.
 */
@Composable
private fun ConfettiSuccess(onDone: () -> Unit) {
    val pieces = remember {
        List(28) {
            ConfettiPiece(
                startX = Random.nextFloat(),
                drift = (Random.nextFloat() - 0.5f) * 0.3f,
                color = confettiColors[Random.nextInt(confettiColors.size)],
                rotation = Random.nextFloat() * 360f,
                size = 8f + Random.nextFloat() * 8f,
            )
        }
    }
    var started by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 1500, easing = LinearEasing),
        label = "confetti",
    )

    LaunchedEffect(Unit) {
        started = true
        delay(2000)
        onDone()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            pieces.forEach { piece ->
                val x = (piece.startX + piece.drift * progress) * size.width
                val y = progress * size.height
                drawCircle(
                    color = piece.color,
                    radius = piece.size,
                    center = Offset(x, y),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = confettiColors.first(),
                modifier = Modifier.padding(8.dp),
            )
            Text(
                "Subscription cancelled",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}
