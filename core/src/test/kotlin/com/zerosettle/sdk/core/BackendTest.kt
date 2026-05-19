package com.zerosettle.sdk.core

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class BackendTest {
    private lateinit var server: MockWebServer
    private lateinit var backend: Backend

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        backend = Backend(
            baseUrl = server.url("/").toString().trimEnd('/'),
            publishableKey = "zs_pk_test_abc",
            sdkVersion = "1.0.0",
        )
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun fetchProducts_decodesCatalog() = runTest {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        val res = backend.fetchProducts(userId = "u1")
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrNull()?.products).isEmpty()
        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("/v1/iap/products/")
        assertThat(recorded.path).contains("user_id=u1")
    }

    @Test fun fetchEntitlements_decodesEntitlementsAndPendingActions() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"entitlements":[{"id":"ent_1","product_id":"p","source":"web_checkout","is_active":true,"status":"active"}],
                   "pending_actions":[{"type":"manual_play_cancel","product_id":"p"}],
                   "pending_claims":[]}""",
            ),
        )
        val res = backend.fetchEntitlements(userId = "u1")
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrNull()?.entitlements).hasSize(1)
        assertThat(res.getOrNull()?.pendingActions).hasSize(1)
        assertThat(server.takeRequest().path).contains("/v1/iap/entitlements/")
    }

    @Test fun fetchUserOffer_decodesResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"user_id":"u1","app_id":1,"is_sandbox":true,
                   "subscription":{"type":"none"},
                   "offer":{"action_type":"no_action","is_eligible":false,"checkout_product_id":""},
                   "server_time":"2026-05-12T00:00:00Z"}""",
            ),
        )
        val res = backend.fetchUserOffer(userId = "u1")
        assertThat(res.getOrNull()?.isEligible).isFalse()
        assertThat(server.takeRequest().path).contains("/v1/iap/user-offer/")
    }

    @Test fun createCheckoutConfig_postsBodyVerbatim() = runTest {
        server.enqueue(MockResponse().setBody("""{"checkout_url":"https://x"}"""))
        val res = backend.createCheckoutConfig(body = """{"user_id":"u1","product_id":"p","play_purchase_token":"tok"}""")
        assertThat(res.isSuccess).isTrue()
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/iap/checkout-configs/")
        assertThat(recorded.body.readUtf8()).contains("\"play_purchase_token\":\"tok\"")
    }

    @Test fun dismissMigrationAction_postsUrlAndBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        backend.dismissMigrationAction(transactionId = "zs_txn_1", userId = "u1", actionType = "info_banner_dismissed")
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1/iap/migration-actions/zs_txn_1/dismiss/")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"user_id\":\"u1\"")
        assertThat(body).contains("\"action_type\":\"info_banner_dismissed\"")
    }

    private suspend fun callSyncPlayPurchase() = backend.syncPlayPurchase(
        userId = "u1",
        purchaseToken = "tok",
        productId = "pro_monthly",
        packageName = "com.app",
        orderId = "GPA",
        purchaseState = 1,
        isAcknowledged = false,
        signature = "sig",
        originalJson = "{}",
        willAutoRenew = true,
        customerName = null,
        customerEmail = null,
    )

    @Test fun syncPlayPurchase_postsAndDecodesResponse() = runTest {
        // transaction_id is a Django PK — a JSON integer, not a quoted string.
        server.enqueue(MockResponse().setBody("""{"owned":true,"transaction_id":7296}"""))
        val res = callSyncPlayPurchase()
        assertThat(res.getOrNull()?.owned).isTrue()
        assertThat(res.getOrNull()?.transactionId).isEqualTo(7296L)
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/iap/play-store-transactions/")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"purchase_token\":\"tok\"")
        assertThat(body).contains("\"package_name\":\"com.app\"")
    }

    /**
     * RC-E regression: the backend returns `transaction_id`/`entitlement_id`
     * as JSON integers (Django PKs). Decoding them into `String` properties
     * under the SDK's strict `Json` config throws `JsonDecodingException`,
     * which made every Play sync return `Result.failure` on an HTTP 200 —
     * so consumable purchases never reported `owned=true`. These cases lock
     * in the realistic full-body wire shape.
     */
    @Test fun syncPlayPurchase_decodesConsumableResponseWithNullEntitlement() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"ok","owned":true,"transaction_id":7296,"entitlement_id":null,
                   "conflict":false,"claim_available":false,"existing_owner_hint":null,"is_sandbox":true}""",
            ),
        )
        val res = callSyncPlayPurchase()
        assertThat(res.isSuccess).isTrue()
        val resp = res.getOrThrow()
        assertThat(resp.owned).isTrue()
        assertThat(resp.transactionId).isEqualTo(7296L)
        assertThat(resp.entitlementId).isNull()
        assertThat(resp.isSandbox).isTrue()
    }

    @Test fun syncPlayPurchase_decodesNonConsumableResponseWithIntegerEntitlementId() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"ok","owned":true,"transaction_id":8401,"entitlement_id":512,
                   "conflict":false,"claim_available":false,"existing_owner_hint":null,"is_sandbox":false}""",
            ),
        )
        val res = callSyncPlayPurchase()
        assertThat(res.isSuccess).isTrue()
        val resp = res.getOrThrow()
        assertThat(resp.owned).isTrue()
        assertThat(resp.transactionId).isEqualTo(8401L)
        assertThat(resp.entitlementId).isEqualTo(512L)
    }

    /**
     * The backend now also returns `transaction_ref` — the canonical `txn_*`
     * string id of the synced transaction. `GET /v1/iap/transactions/{id}/`
     * resolves on this; the integer `transaction_id` PK 404s there. The SDK
     * must decode it so the post-purchase fetch can use the right id.
     */
    @Test fun syncPlayPurchase_decodesTransactionRef() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"ok","owned":true,"transaction_id":7300,
                   "transaction_ref":"txn_7300","entitlement_id":null,"is_sandbox":false}""",
            ),
        )
        val res = callSyncPlayPurchase()
        assertThat(res.isSuccess).isTrue()
        val resp = res.getOrThrow()
        assertThat(resp.transactionRef).isEqualTo("txn_7300")
        assertThat(resp.transactionId).isEqualTo(7300L)
    }

    /** Older backend with no `transaction_ref` field still decodes; ref is null. */
    @Test fun syncPlayPurchase_transactionRefAbsent_decodesAsNull() = runTest {
        server.enqueue(MockResponse().setBody("""{"owned":true,"transaction_id":7300}"""))
        val res = callSyncPlayPurchase()
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrThrow().transactionRef).isNull()
    }

    @Test fun initiatePlayUcb_postsBodyAndDecodesResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "client_secret":"pi_secret_xyz",
                    "stripe_account":"acct_test_123",
                    "merchant_country":"US",
                    "external_transaction_id":"abcdef0123456789abcdef0123456789",
                    "transaction_id":42
                }""".trimIndent(),
            ),
        )
        val res = backend.initiatePlayUcb(
            externalTransactionToken = "tok-abc",
            productId = "pro_monthly",
            userId = "user-7",
        )
        assertThat(res.isSuccess).isTrue()
        val resp = res.getOrThrow()
        assertThat(resp.clientSecret).isEqualTo("pi_secret_xyz")
        assertThat(resp.stripeAccount).isEqualTo("acct_test_123")
        assertThat(resp.merchantCountry).isEqualTo("US")
        assertThat(resp.externalTransactionId).isEqualTo("abcdef0123456789abcdef0123456789")
        assertThat(resp.transactionId).isEqualTo(42)
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1/iap/play-ucb/initiate/")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"external_transaction_token\":\"tok-abc\"")
        assertThat(body).contains("\"product_id\":\"pro_monthly\"")
        assertThat(body).contains("\"user_id\":\"user-7\"")
    }

    @Test fun initiatePlayUcb_optionalFieldsTolerateNulls() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "client_secret":"pi_secret_xyz",
                    "stripe_account":null,
                    "merchant_country":null,
                    "external_transaction_id":"abcdef0123456789abcdef0123456789"
                }""".trimIndent(),
            ),
        )
        val res = backend.initiatePlayUcb("tok", "pro", "u")
        assertThat(res.isSuccess).isTrue()
        val resp = res.getOrThrow()
        assertThat(resp.stripeAccount).isNull()
        assertThat(resp.merchantCountry).isNull()
        assertThat(resp.transactionId).isNull()
        assertThat(resp.transactionRef).isNull()
    }

    /**
     * FB-2: the backend's `/initiate/` response now also returns
     * `transaction_ref` — the canonical `ucb_*` string id. `GET
     * /v1/iap/transactions/{id}/` resolves on this; the integer
     * `transaction_id` PK 404s there. The SDK must decode it so the UCB
     * post-purchase fetch uses the right id.
     */
    @Test fun initiatePlayUcb_decodesTransactionRef() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "client_secret":"pi_secret_xyz",
                    "external_transaction_id":"abcdef0123456789abcdef0123456789",
                    "transaction_id":42,
                    "transaction_ref":"ucb_abc123"
                }""".trimIndent(),
            ),
        )
        val res = backend.initiatePlayUcb("tok", "pro", "u")
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrThrow().transactionRef).isEqualTo("ucb_abc123")
    }

    /** Older backend with no `transaction_ref` field still decodes; ref is null. */
    @Test fun initiatePlayUcb_transactionRefAbsent_decodesAsNull() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "client_secret":"pi_secret_xyz",
                    "external_transaction_id":"abcdef0123456789abcdef0123456789"
                }""".trimIndent(),
            ),
        )
        val res = backend.initiatePlayUcb("tok", "pro", "u")
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrThrow().transactionRef).isNull()
    }
}
