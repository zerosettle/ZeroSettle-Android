package com.zerosettle.sdk.models

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class EntitlementTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val fixture = """
    {
      "id": "ent_1",
      "product_id": "pro_monthly",
      "source": "play_store",
      "is_active": true,
      "status": "active",
      "expires_at": "2026-06-11T00:00:00Z",
      "will_renew": true,
      "is_trial": false,
      "purchased_at": "2026-05-11T00:00:00Z"
    }
    """.trimIndent()

    @Test fun decode_populatesFields() {
        val e = json.decodeFromString(Entitlement.serializer(), fixture)
        assertThat(e.id).isEqualTo("ent_1")
        assertThat(e.source).isEqualTo(EntitlementSource.PLAY_STORE)
        assertThat(e.isActive).isTrue()
        assertThat(e.status).isEqualTo(EntitlementStatus.ACTIVE)
        assertThat(e.willRenew).isTrue()
    }

    @Test fun unknownStatus_decodesAsUnknown() {
        val unknown = fixture.replace("\"active\"", "\"future_state\"")
        val e = json.decodeFromString(Entitlement.serializer(), unknown)
        assertThat(e.status).isEqualTo(EntitlementStatus.UNKNOWN)
        assertThat(e.statusRaw).isEqualTo("future_state")
    }
}
