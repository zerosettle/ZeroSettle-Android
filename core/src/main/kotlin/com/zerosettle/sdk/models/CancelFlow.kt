package com.zerosettle.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-driven cancel / retention flow. Mirrors iOS `CancelFlow.*` — the backend supplies
 * survey questions, an optional save offer, pause durations, deflection resources, and a
 * downgrade option; the SDK renders them and reports the outcome.
 *
 * Wire shape: `GET /v1/iap/cancel-flow/` returns
 * `{enabled, questions[], offer?, pause?, deflect?, downgrade?, variant_id?}`.
 *
 * **Option objects (1.3.0):** `questions[].options` are objects (not strings). Options carry
 * `triggers_offer`, `triggers_pause`, and `route` for SDK-side routing decisions.
 * `route` is nullable because the live backend does not yet emit it — the model default-nulls
 * it so both the fixture shape and the current live shape decode without error.
 *
 * **Deflect / downgrade / offer duration:** also nullable — the live backend does not yet emit
 * these fields; they decode from the full fixture (and a future backend) without breaking older
 * tenants that have not configured them.
 */
public object CancelFlow {

    // TODO(Task 3.4): 1.3.0 always receives the `full` (object-options) shape — the
    // legacy `legacy_android` (string-options) shape is NOT decoded here. If a stale
    // cache could ever serve string options to a 1.3.0 client, add a polymorphic
    // string-OR-object `Option` deserializer. Deferred resilience item (no real
    // exposure today: the backend gates the wire shape by SDK version+platform).

    // ─── Option object ────────────────────────────────────────────────────────

    @Serializable
    public data class Option(
        val id: Int,
        val order: Int,
        val label: String,
        @SerialName("icon_name") val iconName: String? = null,
        val subtitle: String? = null,
        @SerialName("triggers_offer") val triggersOffer: Boolean = false,
        @SerialName("triggers_pause") val triggersPause: Boolean = false,
        /** Routing hint from the backend. Nullable — live backend does not emit this yet. */
        val route: String? = null,
    )

    // ─── Question ─────────────────────────────────────────────────────────────

    @Serializable
    public data class Question(
        val id: Int,
        val order: Int,
        @SerialName("question_text") val questionText: String,
        @SerialName("question_type") val questionType: String,
        @SerialName("is_required") val isRequired: Boolean = false,
        val options: List<Option> = emptyList(),
    )

    // ─── Offer ────────────────────────────────────────────────────────────────

    @Serializable
    public data class Offer(
        val enabled: Boolean = false,
        val title: String? = null,
        val body: String? = null,
        @SerialName("cta_text") val ctaText: String? = null,
        val type: String? = null,
        val value: String? = null,
        /** Present on `free_period` offer types; live backend does not emit this yet. */
        val duration: Int? = null,
        @SerialName("duration_unit") val durationUnit: String? = null,
    )

    // ─── Pause ────────────────────────────────────────────────────────────────

    @Serializable
    public data class PauseOption(
        val id: Int,
        val order: Int,
        val label: String,
        @SerialName("duration_type") val durationType: String,
        @SerialName("duration_days") val durationDays: Int? = null,
        @SerialName("resume_date") val resumeDate: String? = null,
    )

    @Serializable
    public data class Pause(
        val enabled: Boolean = false,
        val title: String? = null,
        val body: String? = null,
        @SerialName("cta_text") val ctaText: String? = null,
        val options: List<PauseOption> = emptyList(),
    )

    // ─── Deflect ──────────────────────────────────────────────────────────────

    @Serializable
    public data class DeflectItem(
        val id: Int,
        val order: Int,
        val label: String,
        @SerialName("action_type") val actionType: String,
        @SerialName("action_value") val actionValue: String,
    )

    @Serializable
    public data class Deflect(
        val enabled: Boolean = false,
        val title: String? = null,
        val body: String? = null,
        val items: List<DeflectItem> = emptyList(),
    )

    // ─── Downgrade ────────────────────────────────────────────────────────────

    @Serializable
    public data class Downgrade(
        val enabled: Boolean = false,
        val title: String? = null,
        val body: String? = null,
        @SerialName("cta_text") val ctaText: String? = null,
        @SerialName("target_product_id") val targetProductId: String? = null,
    )

    // ─── Top-level config ─────────────────────────────────────────────────────

    @Serializable
    public data class Config(
        val enabled: Boolean = false,
        val questions: List<Question> = emptyList(),
        val offer: Offer? = null,
        val pause: Pause? = null,
        /** Deflection resources panel. Live backend does not emit this yet. */
        val deflect: Deflect? = null,
        /** Downgrade offer. Live backend does not emit this yet. */
        val downgrade: Downgrade? = null,
        @SerialName("variant_id") val variantId: Int? = null,
    )

    // ─── Result (host-facing sealed class; unchanged from 1.2.1) ─────────────

    public sealed class Result {
        public data object Cancelled : Result()
        public data class Paused(val resumesAt: String?) : Result()
        public data class SaveOfferAccepted(val productId: String) : Result()
        public data object Dismissed : Result()
    }

    // ─── Wire types for Backend.kt calls (unchanged from 1.2.1) ─────────────

    @Serializable
    public data class Response(
        @SerialName("question_id") val questionId: String,
        val answer: String,
    )

    /**
     * Response from `POST /v1/iap/cancel-flow/accept-offer/`.
     *
     * The backend returns `{message, discount_percent, duration_months}` — the
     * legacy [SaveOfferResult] shape has drifted from the wire. Backend.kt still
     * references this type; keeping it here (with nullable fields) so the call
     * compiles and callers that only need the success signal continue to work.
     * A typed rework is deferred to a later phase.
     */
    @Serializable
    public data class SaveOfferResult(
        @SerialName("product_id") val productId: String? = null,
        @SerialName("savings_percent") val savingsPercent: Int? = null,
        val message: String? = null,
        @SerialName("discount_percent") val discountPercent: Int? = null,
        @SerialName("duration_months") val durationMonths: Int? = null,
    )
}
