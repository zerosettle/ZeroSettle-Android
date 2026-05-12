package com.zerosettle.sdk.entitlements

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.PendingAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class PendingActionParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun rows(text: String) = json.parseToJsonElement(text).jsonArray.map { it.jsonObject }

    @Test fun parse_migrationCompletedInfo() {
        val parsed = PendingActionParser.parse(
            rows(
                """[{"type":"migration_completed_info","transaction_id":"txn_1",
                    "play_access_ends_at":"2026-06-01T00:00:00Z",
                    "new_subscription_price":{"amount_cents":499,"currency":"USD","billing_interval":"month"},
                    "user_message":"You switched."}]""",
            ),
        ) { }
        assertThat(parsed).hasSize(1)
        val a = parsed.first() as PendingAction.MigrationCompletedInfo
        assertThat(a.transactionId).isEqualTo("txn_1")
        assertThat(a.newSubscriptionPriceCents).isEqualTo(499)
        assertThat(a.newSubscriptionCurrency).isEqualTo("USD")
        assertThat(a.newSubscriptionInterval).isEqualTo("month")
        assertThat(a.playAccessEndsAtIso).isEqualTo("2026-06-01T00:00:00Z")
        assertThat(a.userMessage).isEqualTo("You switched.")
    }

    @Test fun parse_migrationCompletedInfo_nullableFieldsAbsent() {
        val a = PendingActionParser.parse(
            rows(
                """[{"type":"migration_completed_info","transaction_id":"txn_1","play_access_ends_at":null,
                    "new_subscription_price":null,"user_message":"m"}]""",
            ),
        ) { }.first() as PendingAction.MigrationCompletedInfo
        assertThat(a.playAccessEndsAtIso).isNull()
        assertThat(a.newSubscriptionPriceCents).isNull()
        assertThat(a.newSubscriptionCurrency).isNull()
    }

    @Test fun parse_manualPlayCancel() {
        val a = PendingActionParser.parse(
            rows(
                """[{"type":"manual_play_cancel","transaction_id":"txn_2",
                    "original_play_purchase_token":"ptok_x","expires_at":"2026-06-01T00:00:00Z",
                    "deep_link":"https://play.google.com/store/account/subscriptions?package=com.app",
                    "user_message":"Cancel it now."}]""",
            ),
        ) { }.first() as PendingAction.ManualPlayCancel
        assertThat(a.transactionId).isEqualTo("txn_2")
        assertThat(a.originalPlayPurchaseToken).isEqualTo("ptok_x")
        assertThat(a.expiresAtIso).isEqualTo("2026-06-01T00:00:00Z")
        assertThat(a.deepLink).contains("play.google.com")
    }

    @Test fun parse_unknownType_loggedAndDropped() {
        val logged = mutableListOf<String>()
        val parsed = PendingActionParser.parse(
            rows(
                """[{"type":"future_action_v9","transaction_id":"t","user_message":"?"},
                    {"type":"manual_play_cancel","transaction_id":"t2","original_play_purchase_token":"p","expires_at":"x","deep_link":"y","user_message":"m"}]""",
            ),
        ) { logged += it }
        assertThat(parsed).hasSize(1)
        assertThat(parsed.first()).isInstanceOf(PendingAction.ManualPlayCancel::class.java)
        assertThat(logged).hasSize(1)
        assertThat(logged.first()).contains("future_action_v9")
    }

    @Test fun parse_malformedRow_droppedNotThrown() {
        val parsed = PendingActionParser.parse(
            rows("""[{"type":"manual_play_cancel","transaction_id":"a1"}]"""),  // missing required fields
        ) { }
        assertThat(parsed).isEmpty()
    }

    @Test fun parse_rowWithoutType_dropped() {
        val parsed = PendingActionParser.parse(rows("""[{"transaction_id":"a1","user_message":"m"}]""")) { }
        assertThat(parsed).isEmpty()
    }

    @Test fun parse_emptyList() {
        assertThat(PendingActionParser.parse(emptyList()) { }).isEmpty()
    }
}
