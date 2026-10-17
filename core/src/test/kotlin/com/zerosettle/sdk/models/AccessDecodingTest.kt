package com.zerosettle.sdk.models

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.core.EntitlementsResponse
import kotlinx.serialization.json.Json
import org.junit.Test

class AccessDecodingTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun decode(body: String) = json.decodeFromString(EntitlementsResponse.serializer(), body)

    private val currency =
        """{"key":"credits","name":"Credit","plural":"Credits","glyph":"sparkles","offline":"allow"}"""

    @Test fun featureValuesDecodeBoolOrTextAndSkipOthers() {
        val access = decode(
            """{"features":{"custom_styles":true,"export_quality":"4K","off":false,"bad":3,"nil":null}}""",
        ).access()
        assertThat(access.features).containsExactly(
            "custom_styles", FeatureValue.Bool(true),
            "export_quality", FeatureValue.Text("4K"),
            "off", FeatureValue.Bool(false),
        )
        assertThat(access.isPresent).isTrue()
    }

    @Test fun responseWithoutAccessKeysDecodes() {
        val resp = decode("""{"entitlements":[],"pending_actions":[]}""")
        assertThat(resp.plan).isNull()
        assertThat(resp.addOns).isNull()
        assertThat(resp.access()).isEqualTo(Access.EMPTY)
        assertThat(resp.access().isPresent).isFalse()
    }

    @Test fun fullPayloadDecodes() {
        val access = decode(
            """{"entitlements":[],
              "plan":{"key":"pro","name":"Pro","kind":"tier"},
              "add_ons":[{"key":"extra","name":"Extra","kind":"add_on"}],
              "features":{"seats":"5"},
              "balances":{"credits":{"currency":$currency,"remaining":318,"limit":500,
                "resets_at":"2026-11-03T00:00:00Z","allowance_remaining":300,"rolled_over":0,"purchased":18}}}""",
        ).access()
        assertThat(access.plan).isEqualTo(Plan("pro", "Pro", PlanKind.TIER))
        assertThat(access.addOns).containsExactly(Plan("extra", "Extra", PlanKind.ADD_ON))
        assertThat(access.features["seats"]).isEqualTo(FeatureValue.Text("5"))
        val b = access.balances.getValue("credits")
        assertThat(b.remaining).isEqualTo(318)
        assertThat(b.limit).isEqualTo(500)
        assertThat(b.resetsAt).isEqualTo("2026-11-03T00:00:00Z")
        assertThat(b.allowanceRemaining).isEqualTo(300)
        assertThat(b.purchased).isEqualTo(18)
        assertThat(b.currency.plural).isEqualTo("Credits")
        assertThat(access.isPresent).isTrue()
    }

    @Test fun unknownPlanKindTolerated() {
        val access = decode("""{"plan":{"key":"x","name":"X","kind":"bundle"}}""").access()
        assertThat(access.plan?.kind).isEqualTo(PlanKind.UNKNOWN)
        assertThat(access.plan?.key).isEqualTo("x")
    }

    @Test fun balanceNullLimitAndResetsAt() {
        val access = decode(
            """{"balances":{"credits":{"currency":$currency,"remaining":18,"limit":null,
              "resets_at":null,"allowance_remaining":0,"rolled_over":0,"purchased":18}}}""",
        ).access()
        val b = access.balances.getValue("credits")
        assertThat(b.limit).isNull()
        assertThat(b.resetsAt).isNull()
    }

    @Test fun malformedBalanceIsSkippedAlone() {
        val access = decode(
            """{"plan":{"key":"pro","name":"Pro","kind":"tier"},"features":{"a":true},
              "balances":{"bad":{"remaining":"lots"},
                "credits":{"currency":$currency,"remaining":5,"limit":null,"resets_at":null,
                "allowance_remaining":0,"rolled_over":0,"purchased":5}}}""",
        ).access()
        assertThat(access.balances.keys).containsExactly("credits")
        assertThat(access.plan?.key).isEqualTo("pro")
        assertThat(access.features).containsKey("a")
    }

    @Test fun malformedPlanNeverFailsEntitlements() {
        val ent = """{"id":"e1","product_id":"pro","source":"web_checkout","is_active":true,"status":"active"}"""
        for (bad in listOf("\"oops\"", """{"key":5}""")) {
            val resp = decode("""{"entitlements":[$ent],"plan":$bad,"add_ons":[{"key":5},{"key":"a","name":"A","kind":"add_on"}]}""")
            assertThat(resp.entitlements).hasSize(1)
            val access = resp.access()
            assertThat(access.plan).isNull()
            assertThat(access.addOns).containsExactly(Plan("a", "A", PlanKind.ADD_ON))
        }
    }
}
