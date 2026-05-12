package com.zerosettle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.models.CancelFlow

private enum class CancelStep { QUESTION, OFFER, PAUSE }

/**
 * Renders the server-driven retention / cancel flow: questionnaire → save offer →
 * pause options. The host supplies [config] (from `ZeroSettle.fetchCancelFlowConfig()`);
 * on a terminal choice [onResult] fires with a [CancelFlow.Result] — the host then calls
 * the matching `ZeroSettle.*` method (`acceptSaveOffer` / pause / cancel). Mirrors iOS's
 * cancel-flow sheet.
 */
@Composable
public fun ZeroSettleCancelFlow(
    config: CancelFlow.Config,
    onResult: (CancelFlow.Result) -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember {
        mutableStateOf(
            when {
                config.questions.isNotEmpty() -> CancelStep.QUESTION
                config.saveOffer != null -> CancelStep.OFFER
                else -> CancelStep.PAUSE
            },
        )
    }
    val question = config.questions.firstOrNull()
    val saveOffer = config.saveOffer

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (step) {
            CancelStep.QUESTION -> {
                Text(question?.prompt.orEmpty(), style = MaterialTheme.typography.titleMedium)
                question?.options?.forEach { opt ->
                    OutlinedButton(
                        onClick = { step = if (saveOffer != null) CancelStep.OFFER else CancelStep.PAUSE },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(opt) }
                }
            }
            CancelStep.OFFER -> {
                if (saveOffer == null) {
                    step = CancelStep.PAUSE
                } else {
                    Text(saveOffer.copy, style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { onResult(CancelFlow.Result.SaveOfferAccepted(saveOffer.productId)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Keep my plan") }
                    TextButton(onClick = { step = CancelStep.PAUSE }, modifier = Modifier.fillMaxWidth()) { Text("No thanks") }
                }
            }
            CancelStep.PAUSE -> {
                if (config.pauseOptionsDays.isNotEmpty()) {
                    Text("Pause instead?", style = MaterialTheme.typography.titleMedium)
                    config.pauseOptionsDays.forEach { days ->
                        OutlinedButton(
                            onClick = { onResult(CancelFlow.Result.Paused(resumesAt = null)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Pause for $days days") }
                    }
                }
                Button(onClick = { onResult(CancelFlow.Result.Cancelled) }, modifier = Modifier.fillMaxWidth()) { Text("Cancel anyway") }
                TextButton(onClick = { onResult(CancelFlow.Result.Dismissed) }, modifier = Modifier.fillMaxWidth()) { Text("Never mind") }
            }
        }
    }
}
