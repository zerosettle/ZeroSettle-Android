package com.zerosettle.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cancel flow configuration returned by the backend.
 * Controls whether to present the cancellation questionnaire and its content.
 */
@Serializable
data class CancelFlowConfig(
    val enabled: Boolean,
    val questions: List<CancelFlowQuestion> = emptyList(),
    val offer: CancelFlowOffer? = null,
    val pause: CancelFlowPauseConfig? = null,
)

/**
 * A single question in the cancel flow questionnaire.
 */
@Serializable
data class CancelFlowQuestion(
    val id: Int,
    val order: Int,
    @SerialName("question_text") val questionText: String,
    @SerialName("question_type") val questionType: CancelFlowQuestionType,
    @SerialName("is_required") val isRequired: Boolean,
    val options: List<CancelFlowOption> = emptyList(),
)

/**
 * The type of a cancel flow question.
 */
@Serializable
enum class CancelFlowQuestionType {
    @SerialName("single_select")
    SINGLE_SELECT,

    @SerialName("free_text")
    FREE_TEXT,
}

/**
 * An answer option for a single-select question.
 */
@Serializable
data class CancelFlowOption(
    val id: Int,
    val order: Int,
    val label: String,
    @SerialName("triggers_offer") val triggersOffer: Boolean,
    @SerialName("triggers_pause") val triggersPause: Boolean = false,
)

/**
 * Save offer configuration shown to retain the user.
 */
@Serializable
data class CancelFlowOffer(
    val enabled: Boolean,
    val title: String,
    val body: String,
    @SerialName("cta_text") val ctaText: String,
    val type: String,
    val value: String,
)

/**
 * Pause configuration for the cancel flow retention page.
 * When enabled, offers the user the option to pause their subscription
 * instead of cancelling.
 */
@Serializable
data class CancelFlowPauseConfig(
    val enabled: Boolean,
    val title: String,
    val body: String,
    @SerialName("cta_text") val ctaText: String,
    val options: List<CancelFlowPauseOption> = emptyList(),
)

/**
 * The type of pause duration.
 */
@Serializable
enum class CancelFlowDurationType {
    @SerialName("days")
    DAYS,

    @SerialName("fixed_date")
    FIXED_DATE;
}

/**
 * A pause duration option presented to the user.
 */
@Serializable
data class CancelFlowPauseOption(
    val id: Int,
    val order: Int,
    val label: String,
    @SerialName("duration_type") val durationType: CancelFlowDurationType,
    @SerialName("duration_days") val durationDays: Int? = null,
    @SerialName("resume_date") val resumeDate: String? = null,
)

/**
 * The outcome of a cancel flow presentation.
 * Maps to iOS `CancelFlow.Result`.
 */
sealed interface CancelFlowResult {
    /** The user completed the flow and chose to cancel. */
    data object Cancelled : CancelFlowResult

    /** The user accepted the save offer and was retained. */
    data object Retained : CancelFlowResult

    /** The user dismissed the sheet without completing the flow. */
    data object Dismissed : CancelFlowResult

    /** The user chose to pause their subscription. */
    data class Paused(val resumesAt: String?) : CancelFlowResult
}

/** Wire-safe name for a [CancelFlowResult], used for backend payloads and logging. */
val CancelFlowResult.outcomeName: String
    get() = when (this) {
        is CancelFlowResult.Cancelled -> "cancelled"
        is CancelFlowResult.Retained -> "retained"
        is CancelFlowResult.Dismissed -> "dismissed"
        is CancelFlowResult.Paused -> "paused"
    }

// -- Headless Cancel Flow Types --

/**
 * Result returned after a save offer is successfully applied.
 */
data class CancelFlowSaveOfferResult(
    /** Human-readable description of the applied offer (e.g., "40% off for 3 months"). */
    val message: String,
    /** Discount percentage, if the offer type is a percentage discount. */
    val discountPercent: Int?,
    /** Duration of the discount in months, if applicable. */
    val durationMonths: Int?,
)

/**
 * The outcome of a cancel flow for analytics tracking.
 * Maps to iOS `CancelFlow.Outcome`.
 */
@Serializable
enum class CancelFlowOutcome {
    @SerialName("cancelled")
    CANCELLED,

    @SerialName("retained")
    RETAINED,

    @SerialName("paused")
    PAUSED,

    @SerialName("dismissed")
    DISMISSED,
}

/**
 * A single answer to a cancel flow question.
 *
 * For single-select questions, set [selectedOptionId].
 * For free-text questions, set [freeText].
 */
data class CancelFlowAnswer(
    /** The question ID this answer corresponds to. */
    val questionId: Int,
    /** The selected option ID (for single-select questions). */
    val selectedOptionId: Int? = null,
    /** The free text response (for free-text questions). */
    val freeText: String? = null,
)

/**
 * Analytics payload submitted after a cancel flow completes.
 *
 * Use with [ZeroSettle.submitCancelFlowResponse] when building custom cancel flow UI.
 */
data class CancelFlowResponse(
    /** The product the user was cancelling. */
    val productId: String,
    /** Your app's user identifier. */
    val userId: String,
    /** The final outcome of the cancel flow. */
    val outcome: CancelFlowOutcome,
    /** The user's answers to questionnaire questions. */
    val answers: List<CancelFlowAnswer> = emptyList(),
    /** Whether the save offer was shown to the user. */
    val offerShown: Boolean = false,
    /** Whether the user accepted the save offer. */
    val offerAccepted: Boolean = false,
    /** Whether the pause option was shown to the user. */
    val pauseShown: Boolean = false,
    /** Whether the user accepted the pause option. */
    val pauseAccepted: Boolean = false,
    /** The selected pause duration in days (if pause was accepted). */
    val pauseDurationDays: Int? = null,
)
