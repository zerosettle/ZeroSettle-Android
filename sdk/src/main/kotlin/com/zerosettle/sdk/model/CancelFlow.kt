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
 * The outcome of a cancel flow presentation.
 * Maps to iOS `CancelFlow.Result`.
 */
enum class CancelFlowResult {
    /** The user completed the flow and chose to cancel. */
    CANCELLED,
    /** The user accepted the save offer and was retained. */
    RETAINED,
    /** The user dismissed the sheet without completing the flow. */
    DISMISSED,
}
