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

    @Test fun userOffer_migrateStorekitToWeb_withPlaySource_decodes() {
        // A realistic `migrate_storekit_to_web` offer carrying the (optional, nested)
        // `source` field — matches `OfferEligibility.to_json_dict()` in the backend.
        val fixture = """
        {
          "user_id": "u1", "app_id": 7, "is_sandbox": false,
          "subscription": {"type": "active_storekit", "product_id": "pro_monthly"},
          "offer": {
            "action_type": "migrate_storekit_to_web",
            "is_eligible": true,
            "checkout_product_id": "pro_monthly_web",
            "from_product_id": "pro_monthly",
            "savings_percent": 20,
            "free_trial_days": 7,
            "min_subscription_days": 14,
            "max_subscription_days": null,
            "rollout_percent": 100,
            "display": {
              "title": "Save 20%", "body": "Switch to direct billing", "cta_text": "Switch now",
              "dismiss_text": "Not now", "accepted_title": "Almost done", "accepted_body": "Finishing up",
              "completed_title": "All set", "completed_body": "Welcome", "apple_cancel_instructions": "Cancel in Settings"
            },
            "proration": null,
            "requires_apple_cancel": true,
            "apple_subscription": {"is_active": true, "expires_at": null, "status_code": 1, "auto_renew_enabled": true},
            "checkout_presentation": "webview",
            "experiment_variant_id": null,
            "source": "play_store"
          },
          "server_time": "2026-05-11T00:00:00Z"
        }
        """.trimIndent()
        val r = json.decodeFromString(UserOffer.Response.serializer(), fixture)
        assertThat(r.isEligible).isTrue()
        assertThat(r.offer.actionType).isEqualTo(UserOffer.ActionType.MIGRATE_STOREKIT_TO_WEB)
        assertThat(r.offer.source).isEqualTo(UserOffer.SourceStorefront.PLAY_STORE)
        assertThat(r.offer.needsStoreCancel).isTrue()
        assertThat(r.offer.isHeadlessUpgrade).isFalse()
        assertThat(r.offer.checkoutPresentation).isEqualTo(UserOffer.CheckoutPresentation.WEBVIEW)
        assertThat(r.offer.appleSubscription?.isActive).isTrue()
        assertThat(r.eligibleOffer).isNotNull()
        assertThat(r.subscription.kind).isEqualTo(UserOffer.Subscription.Kind.ACTIVE_STOREKIT)
    }

    @Test fun userOffer_noAction_decodes_andHasNoEligibleOffer() {
        // The backend omits `source` entirely when null — `ignoreUnknownKeys` / the
        // nullable default both cover that.
        val fixture = """
        {
          "user_id": "u1", "app_id": 7, "is_sandbox": true,
          "subscription": {"type": "none"},
          "offer": {
            "action_type": "no_action",
            "is_eligible": false,
            "checkout_product_id": "",
            "from_product_id": null,
            "savings_percent": 0,
            "free_trial_days": 0,
            "min_subscription_days": 0,
            "max_subscription_days": null,
            "rollout_percent": 100,
            "display": null,
            "proration": null,
            "requires_apple_cancel": false,
            "apple_subscription": null,
            "checkout_presentation": null,
            "experiment_variant_id": null
          },
          "server_time": "2026-05-11T00:00:00Z"
        }
        """.trimIndent()
        val r = json.decodeFromString(UserOffer.Response.serializer(), fixture)
        assertThat(r.isEligible).isFalse()
        assertThat(r.offer.actionType).isEqualTo(UserOffer.ActionType.NO_ACTION)
        assertThat(r.offer.source).isNull()
        assertThat(r.eligibleOffer).isNull()
        assertThat(r.subscription.kind).isEqualTo(UserOffer.Subscription.Kind.NONE)
    }

    @Test fun userOffer_webToWeb_isHeadlessAndDoesNotNeedStoreCancel() {
        val fixture = """
        {
          "user_id": "u1", "app_id": 7, "is_sandbox": false,
          "subscription": {"type": "active_web", "product_id": "pro_monthly"},
          "offer": {
            "action_type": "upgrade_web_to_web", "is_eligible": true,
            "checkout_product_id": "pro_yearly", "from_product_id": "pro_monthly",
            "savings_percent": 10, "free_trial_days": 0, "min_subscription_days": 0,
            "max_subscription_days": null, "rollout_percent": 100,
            "display": null, "proration": {"amount_cents": 199, "currency": "USD", "next_billing_date": null},
            "requires_apple_cancel": false, "apple_subscription": null,
            "checkout_presentation": null, "experiment_variant_id": null
          },
          "server_time": "2026-05-11T00:00:00Z"
        }
        """.trimIndent()
        val r = json.decodeFromString(UserOffer.Response.serializer(), fixture)
        assertThat(r.offer.isHeadlessUpgrade).isTrue()
        assertThat(r.offer.needsStoreCancel).isFalse()
        assertThat(r.offer.proration?.amountCents).isEqualTo(199)
    }

    @Test fun upgradeOfferConfig_legacyDisplay_decodes() {
        val fixture = """
        {"from_product_id":"pro_monthly","to_product_id":"pro_yearly","savings_percent":20,
         "display":{"offer_title":"Go yearly","offer_message":"Save 20%","offer_cta":"Upgrade",
           "accepted_title":"","accepted_message":"","accepted_cta":"","completed_title":"","completed_message":""}}
        """.trimIndent()
        val cfg = json.decodeFromString(UpgradeOffer.Config.serializer(), fixture)
        assertThat(cfg.toProductId).isEqualTo("pro_yearly")
        assertThat(cfg.display.offerTitleOrDefault("F")).isEqualTo("Go yearly")
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

}
