package com.zerosettle.sdk.models

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ModelSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun checkoutTransaction_decodesFromFixture() {
        val fixture = """
        {
          "id": "txn_1", "product_id": "pro_monthly", "status": "completed",
          "source": "web_checkout", "purchased_at": "2026-05-11T00:00:00Z",
          "amount_cents": 499, "currency": "USD"
        }
        """.trimIndent()
        val txn = json.decodeFromString(CheckoutTransaction.serializer(), fixture)
        assertThat(txn.id).isEqualTo("txn_1")
        assertThat(txn.status).isEqualTo(CheckoutTransaction.Status.COMPLETED)
    }

    @Test fun pendingClaim_decodes() {
        val fixture = """
        {"product_id":"pro_monthly","original_transaction_id":"otid","existing_owner_hint":"al***"}
        """.trimIndent()
        val pc = json.decodeFromString(PendingClaim.serializer(), fixture)
        assertThat(pc.productId).isEqualTo("pro_monthly")
        assertThat(pc.existingOwnerHint).isEqualTo("al***")
    }

    @Test fun offerData_play_to_web_upgrade_decodes() {
        val fixture = """
        {
          "flow_type": "upgrade",
          "upgrade_type": "play_to_web",
          "source_storefront": "play_store",
          "product_id": "pro_yearly",
          "eligible_product_ids": ["pro_monthly"],
          "savings_percent": 20,
          "display": {
            "offer_title":"Save 20%","offer_message":"Switch","offer_cta":"Switch now",
            "accepted_title":"All set","accepted_message":"Done","accepted_cta":"Continue",
            "completed_title":"Switched","completed_message":"Welcome"
          },
          "free_trial_days": 7,
          "min_subscription_days": 14,
          "max_subscription_days": null,
          "rollout_percent": 100,
          "checkout_presentation": "custom_tab"
        }
        """.trimIndent()
        val o = json.decodeFromString(Offer.OfferData.serializer(), fixture)
        assertThat(o.flowType).isEqualTo(Offer.FlowType.UPGRADE)
        assertThat(o.upgradeType).isEqualTo(Offer.UpgradeType.PLAY_TO_WEB)
        assertThat(o.sourceStorefront).isEqualTo(Offer.SourceStorefront.PLAY_STORE)
        assertThat(o.checkoutPresentation).isEqualTo(Offer.CheckoutPresentation.CUSTOM_TAB)
        assertThat(o.needsStoreCancel).isTrue()
    }

    @Test fun offerData_webToWeb_doesNotNeedStoreCancel() {
        val fixture = """
        {
          "flow_type":"upgrade","upgrade_type":"web_to_web","source_storefront":"play_store",
          "product_id":"pro_yearly","eligible_product_ids":["pro_monthly"],
          "savings_percent":10,
          "display":{"offer_title":"","offer_message":"","offer_cta":"",
            "accepted_title":"","accepted_message":"","accepted_cta":"",
            "completed_title":"","completed_message":""},
          "free_trial_days":0,"min_subscription_days":0,"max_subscription_days":null,
          "rollout_percent":100,"checkout_presentation":"sheet"
        }
        """.trimIndent()
        val o = json.decodeFromString(Offer.OfferData.serializer(), fixture)
        assertThat(o.needsStoreCancel).isFalse()
    }

    @Test fun display_orDefault_returnsFallbackWhenBlank() {
        val d = Offer.OfferDisplay(
            offerTitle = "", offerMessage = "x", offerCta = "y",
            acceptedTitle = "", acceptedMessage = "", acceptedCta = "",
            completedTitle = "", completedMessage = "",
        )
        assertThat(d.offerTitleOrDefault("FALLBACK")).isEqualTo("FALLBACK")
        assertThat(d.offerMessageOrDefault("x")).isEqualTo("x")
    }

    @Test fun cancelFlow_config_decodes() {
        val fixture = """
        {"questions":[{"id":"q1","prompt":"Why?","options":["price","other"]}],
         "save_offer":{"product_id":"pro_monthly","savings_percent":50,"copy":"Save 50%"},
         "pause_options_days":[7,14,30]}
        """.trimIndent()
        val cfg = json.decodeFromString(CancelFlow.Config.serializer(), fixture)
        assertThat(cfg.questions).hasSize(1)
        assertThat(cfg.pauseOptionsDays).containsExactly(7, 14, 30).inOrder()
    }

    @Test fun userOffer_response_decodesWithPlaySource() {
        val fixture = """
        {"is_eligible":true,"source":"play_store","offer":{
          "flow_type":"migration","upgrade_type":null,"source_storefront":"play_store",
          "product_id":"pro_monthly","eligible_product_ids":["pro_monthly"],
          "savings_percent":20,
          "display":{"offer_title":"","offer_message":"","offer_cta":"",
            "accepted_title":"","accepted_message":"","accepted_cta":"",
            "completed_title":"","completed_message":""},
          "free_trial_days":7,"min_subscription_days":14,"max_subscription_days":null,
          "rollout_percent":100,"checkout_presentation":"custom_tab"}}
        """.trimIndent()
        val u = json.decodeFromString(UserOffer.Response.serializer(), fixture)
        assertThat(u.isEligible).isTrue()
        assertThat(u.source).isEqualTo(Offer.SourceStorefront.PLAY_STORE)
        assertThat(u.offer?.flowType).isEqualTo(Offer.FlowType.MIGRATION)
    }
}
