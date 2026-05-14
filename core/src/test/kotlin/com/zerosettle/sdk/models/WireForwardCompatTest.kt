package com.zerosettle.sdk.models

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Forward-compat tests for fields where the backend may emit values the SDK
 * doesn't recognize. The SDK should map them to a sentinel UNKNOWN variant
 * rather than crashing the entire surrounding decode.
 *
 * Companion to the audit pass that found `CheckoutTransaction.Status` could
 * receive `"superseded"` (api/iap_views.py:6874 / 8388) and `EntitlementSource`
 * is shielded by per-platform coercion today — one accidental backend change
 * away from a crash.
 */
class WireForwardCompatTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun checkoutTransaction_decodesSupersededStatus() {
        // get_transaction emits `"superseded"` for superseded transactions
        // (web→web plan switches). Without the SUPERSEDED variant the entire
        // decode would have thrown.
        val fixture = """
        {"id":"txn_1","product_id":"pro","status":"superseded","source":"web_checkout",
         "purchased_at":"2026-05-11T00:00:00Z"}
        """.trimIndent()
        val txn = json.decodeFromString(CheckoutTransaction.serializer(), fixture)
        assertThat(txn.status).isEqualTo(CheckoutTransaction.Status.SUPERSEDED)
    }

    @Test fun checkoutTransaction_unknownStatusFallsBack() {
        // Forward-compat: a not-yet-known status value must decode as UNKNOWN
        // so the rest of the response is still usable.
        val fixture = """
        {"id":"txn_2","product_id":"pro","status":"future_state","source":"web_checkout",
         "purchased_at":"2026-05-11T00:00:00Z"}
        """.trimIndent()
        val txn = json.decodeFromString(CheckoutTransaction.serializer(), fixture)
        assertThat(txn.status).isEqualTo(CheckoutTransaction.Status.UNKNOWN)
        assertThat(txn.id).isEqualTo("txn_2")
    }

    @Test fun checkoutTransaction_unknownSourceFallsBack() {
        // The backend defends against unknown EntitlementSource values today
        // via per-platform coercion, but that's defense-in-depth — we want
        // the SDK to be robust to drift regardless.
        val fixture = """
        {"id":"txn_3","product_id":"pro","status":"completed","source":"future_storefront",
         "purchased_at":"2026-05-11T00:00:00Z"}
        """.trimIndent()
        val txn = json.decodeFromString(CheckoutTransaction.serializer(), fixture)
        assertThat(txn.source).isEqualTo(EntitlementSource.UNKNOWN)
        assertThat(txn.status).isEqualTo(CheckoutTransaction.Status.COMPLETED)
    }

    @Test fun entitlement_unknownSourceFallsBack() {
        // Same trap, applied to the Entitlement decode path.
        val fixture = """
        {"id":"ent_x","product_id":"pro","source":"future_storefront","is_active":true,
         "status":"active","purchased_at":"2026-05-11T00:00:00Z"}
        """.trimIndent()
        val ent = json.decodeFromString(Entitlement.serializer(), fixture)
        assertThat(ent.source).isEqualTo(EntitlementSource.UNKNOWN)
        assertThat(ent.isActive).isTrue()
    }

    @Test fun checkoutTransactionStatus_encodesAsWireString() {
        // Round-trip pin: encoder must emit the wire string verbatim so we
        // don't break sub-resources that re-serialize the value (logging,
        // SharedPreferences, plugin bridges, etc.).
        val encoded = json.encodeToString(
            CheckoutTransaction.Status.serializer(),
            CheckoutTransaction.Status.SUPERSEDED,
        )
        assertThat(encoded).isEqualTo("\"superseded\"")
    }

    @Test fun entitlementSource_encodesAsWireString() {
        val encoded = json.encodeToString(
            EntitlementSource.serializer(),
            EntitlementSource.PLAY_STORE,
        )
        assertThat(encoded).isEqualTo("\"play_store\"")
    }
}
