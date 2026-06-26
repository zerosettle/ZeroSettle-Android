package com.zerosettle.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.models.CancelFlow

// Route resolution destination — sealed for exhaustive when; testable without Compose
internal sealed class Destination {
    data object Offer : Destination()
    data object Pause : Destination()
    data object BothOfferAndPause : Destination()
    data object Deflect : Destination()
    data object Downgrade : Destination()
    data object NextOrFinish : Destination()
}

/**
 * Resolves the destination for a given option + config.
 * Pure function — no side effects, testable without Compose context.
 *
 * Priority:
 * 1. option.route non-null and recognized → route-driven (gated on section enabled)
 * 2. option.route == null → triggers fallback (legacy / base-shape)
 * 3. Unrecognized route → NextOrFinish (forward-compat)
 */
internal fun resolveDestination(option: CancelFlow.Option, config: CancelFlow.Config): Destination {
    val route = option.route
    if (route != null) {
        return when (route) {
            "offer" -> if (config.offer?.enabled == true) Destination.Offer else Destination.NextOrFinish
            "pause" -> if (config.pause?.enabled == true) Destination.Pause else Destination.NextOrFinish
            "deflect" -> if (config.deflect?.enabled == true) Destination.Deflect else Destination.NextOrFinish
            "downgrade" -> if (config.downgrade?.enabled == true) Destination.Downgrade else Destination.NextOrFinish
            "none" -> Destination.NextOrFinish
            else -> Destination.NextOrFinish  // unrecognized — forward-compat
        }
    }
    // Nil-route fallback: use triggers booleans (legacy base-shape)
    val showOffer = option.triggersOffer && config.offer?.enabled == true
    val showPause = option.triggersPause && config.pause?.enabled == true
    return when {
        showOffer && showPause -> Destination.BothOfferAndPause
        showOffer -> Destination.Offer
        showPause -> Destination.Pause
        else -> Destination.NextOrFinish
    }
}

private enum class CancelStep { QUESTION, OFFER, PAUSE, DEFLECT, DOWNGRADE }

/**
 * Renders the server-driven retention / cancel flow: questionnaire → save offer →
 * pause options → deflect resources → downgrade option. The host supplies [config]
 * (from `ZeroSettle.fetchCancelFlowConfig()`) and [productId] (the subscription being
 * cancelled); on a terminal choice [onResult] fires with a [CancelFlow.Result] — the
 * host then calls the matching `ZeroSettle.*` method. Mirrors iOS's cancel-flow sheet.
 *
 * Navigation is route-driven: each [CancelFlow.Option] carries a `route` field that
 * determines which screen follows (`"offer"`, `"pause"`, `"deflect"`, `"downgrade"`,
 * `"none"`). When `route` is null the legacy triggers-boolean fallback applies.
 *
 * New results in 1.4.0: [CancelFlow.Result.Downgraded] (CTA tapped — host should call
 * `ZeroSettle.purchase(activity, targetProductId)`) and [CancelFlow.Result.DeflectDismissed]
 * (reserved for host telemetry; not currently emitted by the deflect screen — "Continue
 * cancelling" emits [CancelFlow.Result.Cancelled] instead).
 */
@Composable
public fun ZeroSettleCancelFlow(
    config: CancelFlow.Config,
    productId: String,
    onResult: (CancelFlow.Result) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offer = config.offer?.takeIf { it.enabled }
    val pause = config.pause?.takeIf { it.enabled }
    val deflect = config.deflect?.takeIf { it.enabled }
    val downgrade = config.downgrade?.takeIf { it.enabled }
    val ctx = LocalContext.current

    var step by remember {
        mutableStateOf(
            when {
                config.questions.isNotEmpty() -> CancelStep.QUESTION
                offer != null -> CancelStep.OFFER
                else -> CancelStep.PAUSE
            },
        )
    }
    // Whether pause should follow after an offer screen (set by triggers-both fallback)
    var pendingPause by remember { mutableStateOf(false) }

    val question = config.questions.firstOrNull()

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (step) {
            CancelStep.QUESTION -> {
                Text(question?.questionText.orEmpty(), style = MaterialTheme.typography.titleMedium)
                question?.options?.forEach { opt ->
                    OutlinedButton(
                        onClick = {
                            when (resolveDestination(opt, config)) {
                                is Destination.Offer -> { pendingPause = false; step = CancelStep.OFFER }
                                is Destination.Pause -> step = CancelStep.PAUSE
                                is Destination.BothOfferAndPause -> { pendingPause = true; step = CancelStep.OFFER }
                                is Destination.Deflect -> step = CancelStep.DEFLECT
                                is Destination.Downgrade -> step = CancelStep.DOWNGRADE
                                is Destination.NextOrFinish -> onResult(CancelFlow.Result.Cancelled)
                            }
                        },
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
                        onClick = { onResult(CancelFlow.Result.SaveOfferAccepted(productId = productId)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(offer.ctaText ?: "Keep my plan") }
                    TextButton(
                        onClick = {
                            if (pendingPause && pause != null) step = CancelStep.PAUSE
                            else onResult(CancelFlow.Result.Cancelled)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("No thanks") }
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
            CancelStep.DEFLECT -> {
                if (deflect == null) {
                    onResult(CancelFlow.Result.Cancelled)
                } else {
                    Text(deflect.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    val deflectBody = deflect.body
                    if (!deflectBody.isNullOrBlank()) {
                        Text(deflectBody, style = MaterialTheme.typography.bodyMedium)
                    }
                    deflect.items.sortedBy { it.order }.forEach { item ->
                        OutlinedButton(
                            onClick = {
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.actionValue)))
                                } catch (e: ActivityNotFoundException) {
                                    Log.e("ZeroSettleCancelFlow", "No activity to handle deflect URL: ${item.actionValue}", e)
                                } catch (e: Exception) {
                                    Log.e("ZeroSettleCancelFlow", "Failed to open deflect URL: ${item.actionValue}", e)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(item.label) }
                    }
                    TextButton(onClick = { onResult(CancelFlow.Result.Cancelled) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue cancelling")
                    }
                }
            }
            CancelStep.DOWNGRADE -> {
                val downgradeTargetId = downgrade?.targetProductId
                if (downgrade == null || downgradeTargetId == null) {
                    onResult(CancelFlow.Result.Cancelled)
                } else {
                    Text(downgrade.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    val downgradeBody = downgrade.body
                    if (!downgradeBody.isNullOrBlank()) {
                        Text(downgradeBody, style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = { onResult(CancelFlow.Result.Downgraded(targetProductId = downgradeTargetId)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(downgrade.ctaText ?: "Switch plan") }
                    TextButton(onClick = { onResult(CancelFlow.Result.Cancelled) }, modifier = Modifier.fillMaxWidth()) {
                        Text("No thanks, cancel")
                    }
                }
            }
        }
    }
}
