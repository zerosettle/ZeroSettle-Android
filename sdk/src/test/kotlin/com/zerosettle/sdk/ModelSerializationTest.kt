package com.zerosettle.sdk

import com.zerosettle.sdk.model.CancelFlowConfig
import com.zerosettle.sdk.model.CancelFlowDurationType
import com.zerosettle.sdk.model.CancelFlowQuestionType
import com.zerosettle.sdk.model.CancelFlowResult
import com.zerosettle.sdk.model.CheckoutTransaction
import com.zerosettle.sdk.model.Entitlement
import com.zerosettle.sdk.model.Product
import com.zerosettle.sdk.model.UpgradeOfferConfig
import com.zerosettle.sdk.model.UpgradeOfferResult
import com.zerosettle.sdk.model.UpgradeOfferType
import com.zerosettle.sdk.model.outcomeName
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JSON serialization and deserialization of SDK model classes.
 *
 * All models use `kotlinx.serialization` with snake_case wire format.
 * The Json instance here mirrors the SDK's runtime configuration.
 */
class ModelSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ---------------------------------------------------------------
    // CheckoutTransaction
    // ---------------------------------------------------------------

    @Test
    fun testCheckoutTransactionDeserialization() {
        val payload = """
        {
            "id": "txn_abc123",
            "product_id": "com.app.premium_monthly",
            "status": "completed",
            "source": "web_checkout",
            "purchased_at": "2025-11-15T10:30:00Z",
            "expires_at": "2025-12-15T10:30:00Z",
            "product_name": "Premium Monthly",
            "amount_cents": 999,
            "currency": "USD"
        }
        """.trimIndent()

        val txn = json.decodeFromString<CheckoutTransaction>(payload)

        assertEquals("txn_abc123", txn.id)
        assertEquals("com.app.premium_monthly", txn.productId)
        assertEquals(CheckoutTransaction.Status.COMPLETED, txn.status)
        assertEquals(Entitlement.Source.WEB_CHECKOUT, txn.source)
        assertEquals("2025-11-15T10:30:00Z", txn.purchasedAt)
        assertEquals("2025-12-15T10:30:00Z", txn.expiresAt)
        assertEquals("Premium Monthly", txn.productName)
        assertEquals(999, txn.amountCents)
        assertEquals("USD", txn.currency)
    }

    @Test
    fun testCheckoutTransactionOptionalFieldsMissing() {
        val payload = """
        {
            "id": "txn_minimal",
            "product_id": "com.app.coins100",
            "status": "pending",
            "source": "play_store",
            "purchased_at": "2025-11-15T10:30:00Z"
        }
        """.trimIndent()

        val txn = json.decodeFromString<CheckoutTransaction>(payload)

        assertEquals("txn_minimal", txn.id)
        assertEquals("com.app.coins100", txn.productId)
        assertEquals(CheckoutTransaction.Status.PENDING, txn.status)
        assertEquals(Entitlement.Source.PLAY_STORE, txn.source)
        assertEquals("2025-11-15T10:30:00Z", txn.purchasedAt)
        assertNull(txn.expiresAt)
        assertNull(txn.productName)
        assertNull(txn.amountCents)
        assertNull(txn.currency)
    }

    @Test
    fun testCheckoutTransactionAllStatusValues() {
        val statuses = mapOf(
            "completed" to CheckoutTransaction.Status.COMPLETED,
            "pending" to CheckoutTransaction.Status.PENDING,
            "processing" to CheckoutTransaction.Status.PROCESSING,
            "failed" to CheckoutTransaction.Status.FAILED,
            "refunded" to CheckoutTransaction.Status.REFUNDED,
        )

        for ((wire, expected) in statuses) {
            val payload = """
            {
                "id": "txn_1",
                "product_id": "p1",
                "status": "$wire",
                "source": "web_checkout",
                "purchased_at": "2025-01-01T00:00:00Z"
            }
            """.trimIndent()

            val txn = json.decodeFromString<CheckoutTransaction>(payload)
            assertEquals("Status '$wire' should map to $expected", expected, txn.status)
        }
    }

    // ---------------------------------------------------------------
    // Entitlement
    // ---------------------------------------------------------------

    @Test
    fun testEntitlementDeserialization() {
        val payload = """
        {
            "id": "ent_full",
            "product_id": "com.app.premium_annual",
            "source": "web_checkout",
            "is_active": true,
            "status": "active",
            "expires_at": "2026-11-15T10:30:00Z",
            "purchased_at": "2025-11-15T10:30:00Z",
            "paused_at": "2026-01-01T00:00:00Z",
            "pause_resumes_at": "2026-02-01T00:00:00Z",
            "will_renew": false,
            "is_trial": true,
            "trial_ends_at": "2025-11-22T10:30:00Z",
            "cancelled_at": "2026-01-01T00:00:00Z"
        }
        """.trimIndent()

        val ent = json.decodeFromString<Entitlement>(payload)

        assertEquals("ent_full", ent.id)
        assertEquals("com.app.premium_annual", ent.productId)
        assertEquals(Entitlement.Source.WEB_CHECKOUT, ent.source)
        assertTrue(ent.isActive)
        assertEquals("active", ent.status)
        assertEquals("2026-11-15T10:30:00Z", ent.expiresAt)
        assertEquals("2025-11-15T10:30:00Z", ent.purchasedAt)
        assertEquals("2026-01-01T00:00:00Z", ent.pausedAt)
        assertEquals("2026-02-01T00:00:00Z", ent.pauseResumesAt)
        assertFalse(ent.willRenew)
        assertTrue(ent.isTrial)
        assertEquals("2025-11-22T10:30:00Z", ent.trialEndsAt)
        assertEquals("2026-01-01T00:00:00Z", ent.cancelledAt)
    }

    @Test
    fun testEntitlementSourceValues() {
        val sources = mapOf(
            "store_kit" to Entitlement.Source.STORE_KIT,
            "play_store" to Entitlement.Source.PLAY_STORE,
            "web_checkout" to Entitlement.Source.WEB_CHECKOUT,
        )

        for ((wire, expected) in sources) {
            val payload = """
            {
                "id": "ent_1",
                "product_id": "p1",
                "source": "$wire",
                "is_active": true,
                "purchased_at": "2025-01-01T00:00:00Z"
            }
            """.trimIndent()

            val ent = json.decodeFromString<Entitlement>(payload)
            assertEquals("Source '$wire' should map to $expected", expected, ent.source)
        }
    }

    @Test
    fun testEntitlementDefaultValues() {
        val payload = """
        {
            "id": "ent_defaults",
            "product_id": "p1",
            "is_active": false,
            "purchased_at": "2025-01-01T00:00:00Z"
        }
        """.trimIndent()

        val ent = json.decodeFromString<Entitlement>(payload)

        // source defaults to WEB_CHECKOUT
        assertEquals(Entitlement.Source.WEB_CHECKOUT, ent.source)
        // status defaults to "active"
        assertEquals("active", ent.status)
        // will_renew defaults to true
        assertTrue(ent.willRenew)
        // is_trial defaults to false
        assertFalse(ent.isTrial)
        // All nullable fields default to null
        assertNull(ent.expiresAt)
        assertNull(ent.pausedAt)
        assertNull(ent.pauseResumesAt)
        assertNull(ent.trialEndsAt)
        assertNull(ent.cancelledAt)
    }

    // ---------------------------------------------------------------
    // Product
    // ---------------------------------------------------------------

    @Test
    fun testProductDeserialization() {
        val payload = """
        {
            "id": "com.app.coins100",
            "display_name": "100 Coins",
            "product_description": "A pack of 100 gold coins",
            "type": "consumable",
            "web_price": {
                "amount_micros": 499,
                "currency_code": "USD"
            },
            "storekit_price": {
                "amount_micros": 599,
                "currency_code": "USD"
            },
            "synced_to_asc": true
        }
        """.trimIndent()

        val product = json.decodeFromString<Product>(payload)

        assertEquals("com.app.coins100", product.id)
        assertEquals("100 Coins", product.displayName)
        assertEquals("A pack of 100 gold coins", product.productDescription)
        assertEquals(Product.ProductType.CONSUMABLE, product.type)

        assertNotNull(product.webPrice)
        assertEquals(499, product.webPrice!!.amountCents)
        assertEquals("USD", product.webPrice!!.currencyCode)

        assertNotNull(product.appStorePrice)
        assertEquals(599, product.appStorePrice!!.amountCents)
        assertEquals("USD", product.appStorePrice!!.currencyCode)

        assertTrue(product.syncedToAppStoreConnect)
        assertNull(product.promotion)
    }

    @Test
    fun testProductWithPromotion() {
        val payload = """
        {
            "id": "com.app.premium_monthly",
            "display_name": "Premium Monthly",
            "product_description": "Monthly premium subscription",
            "type": "auto_renewable_subscription",
            "web_price": {
                "amount_micros": 999,
                "currency_code": "USD"
            },
            "promotion": {
                "id": "promo_summer",
                "display_name": "Summer Sale",
                "promotional_price": {
                    "amount_micros": 499,
                    "currency_code": "USD"
                },
                "expires_at": "2025-09-01T00:00:00Z",
                "type": "percent_off"
            }
        }
        """.trimIndent()

        val product = json.decodeFromString<Product>(payload)

        assertEquals(Product.ProductType.AUTO_RENEWABLE_SUBSCRIPTION, product.type)
        assertNotNull(product.promotion)
        assertEquals("promo_summer", product.promotion!!.id)
        assertEquals("Summer Sale", product.promotion!!.displayName)
        assertEquals(499, product.promotion!!.promotionalPrice.amountCents)
        assertEquals("2025-09-01T00:00:00Z", product.promotion!!.expiresAt)
    }

    @Test
    fun testProductOptionalFieldsDefaults() {
        val payload = """
        {
            "id": "com.app.basic",
            "display_name": "Basic",
            "product_description": "Basic access",
            "type": "non_consumable"
        }
        """.trimIndent()

        val product = json.decodeFromString<Product>(payload)

        assertNull(product.webPrice)
        assertNull(product.appStorePrice)
        assertFalse(product.syncedToAppStoreConnect)
        assertNull(product.promotion)
    }

    @Test
    fun testProductTypeValues() {
        val types = mapOf(
            "consumable" to Product.ProductType.CONSUMABLE,
            "non_consumable" to Product.ProductType.NON_CONSUMABLE,
            "auto_renewable_subscription" to Product.ProductType.AUTO_RENEWABLE_SUBSCRIPTION,
            "non_renewing_subscription" to Product.ProductType.NON_RENEWING_SUBSCRIPTION,
        )

        for ((wire, expected) in types) {
            val payload = """
            {
                "id": "p1",
                "display_name": "Product",
                "product_description": "Desc",
                "type": "$wire"
            }
            """.trimIndent()

            val product = json.decodeFromString<Product>(payload)
            assertEquals("Type '$wire' should map to $expected", expected, product.type)
        }
    }

    // ---------------------------------------------------------------
    // UpgradeOffer
    // ---------------------------------------------------------------

    @Test
    fun testUpgradeOfferConfigDeserialization() {
        val payload = """
        {
            "available": true,
            "reason": "eligible_for_annual",
            "current_product": {
                "reference_id": "com.app.monthly",
                "name": "Monthly Plan",
                "price_cents": 999,
                "currency": "USD",
                "duration_days": 30,
                "billing_label": "$9.99/mo"
            },
            "target_product": {
                "reference_id": "com.app.annual",
                "name": "Annual Plan",
                "price_cents": 7999,
                "currency": "USD",
                "duration_days": 365,
                "billing_label": "$79.99/yr",
                "monthly_equivalent_cents": 667
            },
            "savings_percent": 33,
            "upgrade_type": "web_to_web",
            "proration": {
                "proration_amount_cents": 5400,
                "currency": "USD",
                "next_billing_date": 1735689600
            },
            "display": {
                "title": "Upgrade to Annual",
                "body": "Save 33% by switching to annual billing.",
                "cta_text": "Upgrade Now",
                "dismiss_text": "Not Now",
                "storekit_migration_body": "You'll need to cancel your App Store subscription.",
                "cancel_instructions": "Go to Settings > Subscriptions"
            },
            "variant_id": 2
        }
        """.trimIndent()

        val config = json.decodeFromString<UpgradeOfferConfig>(payload)

        assertTrue(config.available)
        assertEquals("eligible_for_annual", config.reason)

        // current_product
        assertNotNull(config.currentProduct)
        assertEquals("com.app.monthly", config.currentProduct!!.referenceId)
        assertEquals("Monthly Plan", config.currentProduct!!.name)
        assertEquals(999, config.currentProduct!!.priceCents)
        assertEquals("USD", config.currentProduct!!.currency)
        assertEquals(30, config.currentProduct!!.durationDays)
        assertEquals("\$9.99/mo", config.currentProduct!!.billingLabel)

        // target_product
        assertNotNull(config.targetProduct)
        assertEquals("com.app.annual", config.targetProduct!!.referenceId)
        assertEquals("Annual Plan", config.targetProduct!!.name)
        assertEquals(7999, config.targetProduct!!.priceCents)
        assertEquals("USD", config.targetProduct!!.currency)
        assertEquals(365, config.targetProduct!!.durationDays)
        assertEquals("\$79.99/yr", config.targetProduct!!.billingLabel)
        assertEquals(667, config.targetProduct!!.monthlyEquivalentCents)

        assertEquals(33, config.savingsPercent)
        assertEquals(UpgradeOfferType.WEB_TO_WEB, config.upgradeType)

        // proration
        assertNotNull(config.proration)
        assertEquals(5400, config.proration!!.prorationAmountCents)
        assertEquals("USD", config.proration!!.currency)
        assertEquals(1735689600L, config.proration!!.nextBillingDate)

        // display
        assertNotNull(config.display)
        assertEquals("Upgrade to Annual", config.display!!.title)
        assertEquals("Save 33% by switching to annual billing.", config.display!!.body)
        assertEquals("Upgrade Now", config.display!!.ctaText)
        assertEquals("Not Now", config.display!!.dismissText)
        assertEquals(
            "You'll need to cancel your App Store subscription.",
            config.display!!.storekitMigrationBody
        )
        assertEquals("Go to Settings > Subscriptions", config.display!!.cancelInstructions)

        assertEquals(2, config.variantId)
    }

    @Test
    fun testUpgradeOfferConfigNotAvailable() {
        val payload = """
        {
            "available": false,
            "reason": "no_active_subscription"
        }
        """.trimIndent()

        val config = json.decodeFromString<UpgradeOfferConfig>(payload)

        assertFalse(config.available)
        assertEquals("no_active_subscription", config.reason)
        assertNull(config.currentProduct)
        assertNull(config.targetProduct)
        assertNull(config.savingsPercent)
        assertNull(config.upgradeType)
        assertNull(config.proration)
        assertNull(config.display)
        assertNull(config.variantId)
    }

    @Test
    fun testUpgradeOfferTypeValues() {
        val types = mapOf(
            "web_to_web" to UpgradeOfferType.WEB_TO_WEB,
            "storekit_to_web" to UpgradeOfferType.STOREKIT_TO_WEB,
        )

        for ((wire, expected) in types) {
            val payload = """
            {
                "available": true,
                "upgrade_type": "$wire"
            }
            """.trimIndent()

            val config = json.decodeFromString<UpgradeOfferConfig>(payload)
            assertEquals("UpgradeOfferType '$wire' should map to $expected", expected, config.upgradeType)
        }
    }

    @Test
    fun testUpgradeOfferResultOutcomeNames() {
        assertEquals("upgraded", UpgradeOfferResult.Upgraded.outcomeName)
        assertEquals("declined", UpgradeOfferResult.Declined.outcomeName)
        assertEquals("dismissed", UpgradeOfferResult.Dismissed.outcomeName)
    }

    // ---------------------------------------------------------------
    // CancelFlow
    // ---------------------------------------------------------------

    @Test
    fun testCancelFlowConfigDeserialization() {
        val payload = """
        {
            "enabled": true,
            "questions": [
                {
                    "id": 1,
                    "order": 1,
                    "question_text": "Why are you cancelling?",
                    "question_type": "single_select",
                    "is_required": true,
                    "options": [
                        {
                            "id": 10,
                            "order": 1,
                            "label": "Too expensive",
                            "triggers_offer": true,
                            "triggers_pause": false
                        },
                        {
                            "id": 11,
                            "order": 2,
                            "label": "Not using it enough",
                            "triggers_offer": false,
                            "triggers_pause": true
                        }
                    ]
                },
                {
                    "id": 2,
                    "order": 2,
                    "question_text": "Any other feedback?",
                    "question_type": "free_text",
                    "is_required": false,
                    "options": []
                }
            ],
            "offer": {
                "enabled": true,
                "title": "Wait! Here's a deal.",
                "body": "Get 50% off your next month.",
                "cta_text": "Accept Offer",
                "type": "percent_off",
                "value": "50"
            },
            "pause": {
                "enabled": true,
                "title": "Take a break instead?",
                "body": "Pause your subscription and come back later.",
                "cta_text": "Pause Subscription",
                "options": [
                    {
                        "id": 100,
                        "order": 1,
                        "label": "1 week",
                        "duration_type": "days",
                        "duration_days": 7,
                        "resume_date": null
                    },
                    {
                        "id": 101,
                        "order": 2,
                        "label": "1 month",
                        "duration_type": "days",
                        "duration_days": 30,
                        "resume_date": null
                    },
                    {
                        "id": 102,
                        "order": 3,
                        "label": "Until March 1",
                        "duration_type": "fixed_date",
                        "duration_days": null,
                        "resume_date": "2026-03-01"
                    }
                ]
            },
            "variant_id": 5
        }
        """.trimIndent()

        val config = json.decodeFromString<CancelFlowConfig>(payload)

        assertTrue(config.enabled)
        assertEquals(5, config.variantId)

        // Questions
        assertEquals(2, config.questions.size)

        val q1 = config.questions[0]
        assertEquals(1, q1.id)
        assertEquals(1, q1.order)
        assertEquals("Why are you cancelling?", q1.questionText)
        assertEquals(CancelFlowQuestionType.SINGLE_SELECT, q1.questionType)
        assertTrue(q1.isRequired)
        assertEquals(2, q1.options.size)

        val opt1 = q1.options[0]
        assertEquals(10, opt1.id)
        assertEquals(1, opt1.order)
        assertEquals("Too expensive", opt1.label)
        assertTrue(opt1.triggersOffer)
        assertFalse(opt1.triggersPause)

        val opt2 = q1.options[1]
        assertEquals(11, opt2.id)
        assertEquals("Not using it enough", opt2.label)
        assertFalse(opt2.triggersOffer)
        assertTrue(opt2.triggersPause)

        val q2 = config.questions[1]
        assertEquals(CancelFlowQuestionType.FREE_TEXT, q2.questionType)
        assertFalse(q2.isRequired)
        assertTrue(q2.options.isEmpty())

        // Offer
        assertNotNull(config.offer)
        assertTrue(config.offer!!.enabled)
        assertEquals("Wait! Here's a deal.", config.offer!!.title)
        assertEquals("Get 50% off your next month.", config.offer!!.body)
        assertEquals("Accept Offer", config.offer!!.ctaText)
        assertEquals("percent_off", config.offer!!.type)
        assertEquals("50", config.offer!!.value)

        // Pause
        assertNotNull(config.pause)
        assertTrue(config.pause!!.enabled)
        assertEquals("Take a break instead?", config.pause!!.title)
        assertEquals("Pause your subscription and come back later.", config.pause!!.body)
        assertEquals("Pause Subscription", config.pause!!.ctaText)
        assertEquals(3, config.pause!!.options.size)

        val pauseOpt1 = config.pause!!.options[0]
        assertEquals(100, pauseOpt1.id)
        assertEquals("1 week", pauseOpt1.label)
        assertEquals(CancelFlowDurationType.DAYS, pauseOpt1.durationType)
        assertEquals(7, pauseOpt1.durationDays)
        assertNull(pauseOpt1.resumeDate)

        val pauseOpt3 = config.pause!!.options[2]
        assertEquals("Until March 1", pauseOpt3.label)
        assertEquals(CancelFlowDurationType.FIXED_DATE, pauseOpt3.durationType)
        assertNull(pauseOpt3.durationDays)
        assertEquals("2026-03-01", pauseOpt3.resumeDate)
    }

    @Test
    fun testCancelFlowConfigDisabled() {
        val payload = """
        {
            "enabled": false
        }
        """.trimIndent()

        val config = json.decodeFromString<CancelFlowConfig>(payload)

        assertFalse(config.enabled)
        assertTrue(config.questions.isEmpty())
        assertNull(config.offer)
        assertNull(config.pause)
        assertNull(config.variantId)
    }

    @Test
    fun testCancelFlowResultOutcomeNames() {
        assertEquals("cancelled", CancelFlowResult.Cancelled.outcomeName)
        assertEquals("retained", CancelFlowResult.Retained.outcomeName)
        assertEquals("dismissed", CancelFlowResult.Dismissed.outcomeName)
        assertEquals("paused", CancelFlowResult.Paused(resumesAt = null).outcomeName)
        assertEquals("paused", CancelFlowResult.Paused(resumesAt = "2026-03-01").outcomeName)
    }

    // ---------------------------------------------------------------
    // ignoreUnknownKeys resilience
    // ---------------------------------------------------------------

    @Test
    fun testIgnoreUnknownKeysResilience() {
        // Simulates the backend adding new fields the SDK doesn't know about yet.
        // With ignoreUnknownKeys = true, deserialization should still succeed.
        val payload = """
        {
            "id": "txn_future",
            "product_id": "p1",
            "status": "completed",
            "source": "web_checkout",
            "purchased_at": "2025-01-01T00:00:00Z",
            "some_new_field": "unexpected_value",
            "another_new_field": 42
        }
        """.trimIndent()

        val txn = json.decodeFromString<CheckoutTransaction>(payload)
        assertEquals("txn_future", txn.id)
        assertEquals(CheckoutTransaction.Status.COMPLETED, txn.status)
    }
}
