package com.zerosettle.sdk

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.CancelFlow
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * TDD: verify [CancelFlow.Config] decodes the `full` fixture exactly.
 *
 * The primary bug this guards (1.2.1): `Question.options` was `List<String>` — the backend sends
 * option OBJECTS, so `Json.decodeFromString` threw `MissingFieldException`. This suite must
 * pass on the rewritten model and must continue to compile/pass on future model changes.
 */
class CancelFlowDecodeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private fun loadFixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    // ── full fixture ──────────────────────────────────────────────────────────

    @Test fun `full fixture decodes without exception`() {
        val raw = loadFixture("cancel_flow_full.json")
        // Must not throw — the 1.2.1 model threw MissingFieldException here.
        val config = json.decodeFromString(CancelFlow.Config.serializer(), raw)
        assertThat(config.enabled).isTrue()
    }

    @Test fun `full fixture - questions decode as Option objects`() {
        val raw = loadFixture("cancel_flow_full.json")
        val config = json.decodeFromString(CancelFlow.Config.serializer(), raw)

        assertThat(config.questions).hasSize(2)
        val q1 = config.questions[0]
        assertThat(q1.questionText).isEqualTo("Why are you canceling?")
        assertThat(q1.questionType).isEqualTo("single_select")
        assertThat(q1.isRequired).isTrue()
        assertThat(q1.options).hasSize(5)

        val opt1 = q1.options[0]
        assertThat(opt1.label).isEqualTo("Too expensive")
        assertThat(opt1.iconName).isEqualTo("dollarsign.circle")
        assertThat(opt1.subtitle).isEqualTo("The price is too high")
        assertThat(opt1.triggersOffer).isTrue()
        assertThat(opt1.triggersPause).isFalse()
        assertThat(opt1.route).isEqualTo("offer")

        val opt2 = q1.options[1]
        assertThat(opt2.route).isEqualTo("pause")
        assertThat(opt2.triggersPause).isTrue()
    }

    @Test fun `full fixture - offer decodes including duration fields`() {
        val raw = loadFixture("cancel_flow_full.json")
        val config = json.decodeFromString(CancelFlow.Config.serializer(), raw)

        val offer = config.offer
        assertThat(offer).isNotNull()
        assertThat(offer!!.enabled).isTrue()
        assertThat(offer.title).isEqualTo("Stay a bit longer?")
        assertThat(offer.type).isEqualTo("free_period")
        assertThat(offer.value).isEqualTo("2")
        assertThat(offer.duration).isEqualTo(2)
        assertThat(offer.durationUnit).isEqualTo("weeks")
    }

    @Test fun `full fixture - pause decodes with typed PauseOption objects`() {
        val raw = loadFixture("cancel_flow_full.json")
        val config = json.decodeFromString(CancelFlow.Config.serializer(), raw)

        val pause = config.pause
        assertThat(pause).isNotNull()
        assertThat(pause!!.enabled).isTrue()
        assertThat(pause.options).hasSize(2)

        val po1 = pause.options[0]
        assertThat(po1.label).isEqualTo("Pause for 1 month")
        assertThat(po1.durationType).isEqualTo("days")
        assertThat(po1.durationDays).isEqualTo(30)
        assertThat(po1.resumeDate).isNull()

        val po2 = pause.options[1]
        assertThat(po2.durationDays).isEqualTo(90)
    }

    @Test fun `full fixture - deflect decodes with items`() {
        val raw = loadFixture("cancel_flow_full.json")
        val config = json.decodeFromString(CancelFlow.Config.serializer(), raw)

        val deflect = config.deflect
        assertThat(deflect).isNotNull()
        assertThat(deflect!!.enabled).isTrue()
        assertThat(deflect.items).hasSize(3)

        val item1 = deflect.items[0]
        assertThat(item1.actionType).isEqualTo("article")
        assertThat(item1.actionValue).isEqualTo("https://help.example.com/getting-started")
    }

    @Test fun `full fixture - downgrade decodes with target_product_id`() {
        val raw = loadFixture("cancel_flow_full.json")
        val config = json.decodeFromString(CancelFlow.Config.serializer(), raw)

        val downgrade = config.downgrade
        assertThat(downgrade).isNotNull()
        assertThat(downgrade!!.enabled).isTrue()
        assertThat(downgrade.targetProductId).isEqualTo("com.example.basic_monthly")
        assertThat(downgrade.ctaText).isEqualTo("Switch & save")
    }

    // ── base (live backend shape — no route/deflect/downgrade/duration_unit) ──

    @Test fun `base shape decodes without throwing`() {
        // Simulates a response from the live backend today:
        // options have no `route`; no deflect, no downgrade; offer has no duration fields.
        val raw = """
            {
              "enabled": true,
              "questions": [
                {
                  "id": 1,
                  "order": 0,
                  "question_text": "Why are you canceling?",
                  "question_type": "single_select",
                  "is_required": true,
                  "options": [
                    {"id": 1, "order": 0, "label": "Too expensive", "icon_name": null, "subtitle": null, "triggers_offer": true, "triggers_pause": false},
                    {"id": 2, "order": 1, "label": "Need to pause", "icon_name": null, "subtitle": null, "triggers_offer": false, "triggers_pause": true}
                  ]
                }
              ],
              "offer": {
                "enabled": true,
                "title": "Stay?",
                "body": "Here is a deal.",
                "cta_text": "Claim it",
                "type": "discount",
                "value": "20"
              },
              "pause": {
                "enabled": true,
                "title": "Need a break?",
                "body": "Pause and come back.",
                "cta_text": "Pause",
                "options": [
                  {"id": 1, "order": 0, "label": "1 month", "duration_type": "days", "duration_days": 30, "resume_date": null}
                ]
              }
            }
        """.trimIndent()

        val config = json.decodeFromString(CancelFlow.Config.serializer(), raw)

        assertThat(config.enabled).isTrue()
        assertThat(config.questions).hasSize(1)
        assertThat(config.questions[0].options[0].route).isNull()
        assertThat(config.deflect).isNull()
        assertThat(config.downgrade).isNull()
        assertThat(config.offer?.duration).isNull()
        assertThat(config.offer?.durationUnit).isNull()
    }

    // ── minimal disabled response ──────────────────────────────────────────────

    @Test fun `disabled response decodes cleanly`() {
        val raw = """{"enabled": false}"""
        val config = json.decodeFromString(CancelFlow.Config.serializer(), raw)
        assertThat(config.enabled).isFalse()
        assertThat(config.questions).isEmpty()
        assertThat(config.offer).isNull()
    }
}
