package com.zerosettle.sdk.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Whether a [Plan] is a base tier or an add-on. Unrecognized wire values decode
 * as [UNKNOWN] so a new kind never fails the surrounding read.
 */
@Serializable(with = PlanKindSerializer::class)
public enum class PlanKind(public val wire: String) {
    TIER("tier"),
    ADD_ON("add_on"),
    UNKNOWN("");

    public companion object {
        public fun fromWire(value: String): PlanKind =
            values().firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}

internal object PlanKindSerializer : KSerializer<PlanKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PlanKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PlanKind =
        PlanKind.fromWire(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: PlanKind) {
        encoder.encodeString(value.wire)
    }
}

/** A plan or add-on the identified user holds. */
@Serializable
public data class Plan(
    val key: String,
    val name: String,
    val kind: PlanKind = PlanKind.UNKNOWN,
)

/** Value of a feature: a switch or a text value. Keys are the developer's own. */
public sealed class FeatureValue {
    public data class Bool(val value: Boolean) : FeatureValue()
    public data class Text(val value: String) : FeatureValue()
}

/** A spendable unit (credits, tokens, and so on) defined by the developer. */
@Serializable
public data class Currency(
    val key: String,
    val name: String,
    val plural: String,
    val glyph: String? = null,
    /**
     * "allow" or "stop": what to do when ZeroSettle can't be reached. A missing value reads as
     * "allow" on purpose: that is the spec's default, so this fails open like the backend does.
     */
    val offline: String = "allow",
)

/** The user's balance in one [Currency]. */
@Serializable
public data class Balance(
    val currency: Currency,
    val remaining: Long,
    val limit: Long? = null,
    /** ISO 8601 UTC string, kept as text like [Entitlement] dates. */
    @SerialName("resets_at") val resetsAt: String? = null,
    @SerialName("allowance_remaining") val allowanceRemaining: Long = 0,
    @SerialName("rolled_over") val rolledOver: Long = 0,
    val purchased: Long = 0,
)

/**
 * What the identified user can do: their plan, add-ons, feature values and
 * currency balances. [isPresent] is false when the backend sent none of them.
 */
public data class Access(
    val plan: Plan?,
    val addOns: List<Plan>,
    val features: Map<String, FeatureValue>,
    val balances: Map<String, Balance>,
    val isPresent: Boolean,
) {
    public companion object {
        public val EMPTY: Access = Access(null, emptyList(), emptyMap(), emptyMap(), false)
    }
}
