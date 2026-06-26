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
 *
 * 1.3.0: `config.questions[].options` are now typed [CancelFlow.Option] objects.
 * The route-driven navigation redesign (Task 3.4) is deferred to the next chunk —
 * this composable keeps the existing QUESTION→OFFER→PAUSE step logic while rendering
 * the new `option.label` field.
 */
@Composable
public fun ZeroSettleCancelFlow(
    config: CancelFlow.Config,
    onResult: (CancelFlow.Result) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offer = config.offer?.takeIf { it.enabled }
    val pause = config.pause?.takeIf { it.enabled }

    var step by remember {
        mutableStateOf(
            when {
                config.questions.isNotEmpty() -> CancelStep.QUESTION
                offer != null -> CancelStep.OFFER
                else -> CancelStep.PAUSE
            },
        )
    }
    val question = config.questions.firstOrNull()

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (step) {
            CancelStep.QUESTION -> {
                Text(question?.questionText.orEmpty(), style = MaterialTheme.typography.titleMedium)
                question?.options?.forEach { opt ->
                    OutlinedButton(
                        onClick = { step = if (offer != null) CancelStep.OFFER else CancelStep.PAUSE },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(opt.label) }
                }
            }
            CancelStep.OFFER -> {
                if (offer == null) {
                    step = CancelStep.PAUSE
                } else {
                    Text(offer.body.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Button(
                        // NOTE: the new Offer shape has no product_id. The host's productId
                        // param (CancelFlowScreen / CancelDebugScreen) is used at the call
                        // site via onResult → SaveOfferAccepted. We pass an empty string here
                        // as a placeholder; callers that already hold productId should use it.
                        // Route-driven rework (Task 3.4) will clean this up properly.
                        onClick = { onResult(CancelFlow.Result.SaveOfferAccepted(productId = "")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(offer.ctaText ?: "Keep my plan") }
                    TextButton(onClick = { step = CancelStep.PAUSE }, modifier = Modifier.fillMaxWidth()) { Text("No thanks") }
                }
            }
            CancelStep.PAUSE -> {
                val pauseOptions = pause?.options.orEmpty()
                if (pauseOptions.isNotEmpty()) {
                    Text(pause?.title ?: "Pause instead?", style = MaterialTheme.typography.titleMedium)
                    pauseOptions.forEach { po ->
                        OutlinedButton(
                            onClick = { onResult(CancelFlow.Result.Paused(resumesAt = po.resumeDate)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(po.label) }
                    }
                }
                Button(onClick = { onResult(CancelFlow.Result.Cancelled) }, modifier = Modifier.fillMaxWidth()) { Text("Cancel anyway") }
                TextButton(onClick = { onResult(CancelFlow.Result.Dismissed) }, modifier = Modifier.fillMaxWidth()) { Text("Never mind") }
            }
        }
    }
}
