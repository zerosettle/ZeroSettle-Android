package com.zerosettle.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-driven cancel / retention flow. Mirrors iOS `CancelFlow.*` — the backend
 * supplies survey questions, an optional save offer, and pause durations; the SDK
 * renders them and reports the outcome.
 */
public object CancelFlow {

    @Serializable
    public data class Question(
        val id: String,
        val prompt: String,
        val options: List<String>,
    )

    @Serializable
    public data class SaveOffer(
        @SerialName("product_id") val productId: String,
        @SerialName("savings_percent") val savingsPercent: Int,
        val copy: String,
    )

    @Serializable
    public data class Config(
        val questions: List<Question> = emptyList(),
        @SerialName("save_offer") val saveOffer: SaveOffer? = null,
        @SerialName("pause_options_days") val pauseOptionsDays: List<Int> = emptyList(),
    )

    @Serializable
    public data class Response(
        @SerialName("question_id") val questionId: String,
        val answer: String,
    )

    public sealed class Result {
        public data object Cancelled : Result()
        public data class Paused(val resumesAt: String?) : Result()
        public data class SaveOfferAccepted(val productId: String) : Result()
        public data object Dismissed : Result()
    }

    @Serializable
    public data class SaveOfferResult(
        @SerialName("product_id") val productId: String,
        @SerialName("savings_percent") val savingsPercent: Int,
    )
}
