package com.zerosettle.sdk.billing

import com.android.billingclient.api.UserChoiceDetails
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.core.LogcatLogger
import com.zerosettle.sdk.core.ZeroSettleLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UcbChoiceHandler contract (Phase 2 Chunk B):
 *
 *  - When Google's choice screen fires with a valid token + product + userId,
 *    the handler hands off to [UcbCheckoutLauncher.launch] with the same
 *    values. This is the path that, once Chunk C lands, will POST to
 *    /v1/iap/play-ucb/initiate/ and present Stripe PaymentSheet.
 *  - Missing/empty token, missing product list, or missing userId all drop
 *    the callback silently (warn-log only). We intentionally do NOT throw or
 *    surface a Result — the listener has no return channel back to Play, and
 *    the upstream call site (purchase deferred) will time out / surface its
 *    own error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UcbChoiceHandlerTest {

    private val logger: ZeroSettleLogger = LogcatLogger

    private class RecordingLauncher : UcbCheckoutLauncher {
        data class Call(val token: String, val productId: String, val userId: String)
        val calls = mutableListOf<Call>()
        override suspend fun launch(token: String, productId: String, userId: String): Result<Unit> {
            calls += Call(token, productId, userId)
            return Result.success(Unit)
        }
    }

    /**
     * Build a [UserChoiceDetails] via its package-private JSON constructor.
     * PBL 7.1 marks the class `final` and exposes only getters publicly, so
     * we reflect into the `UserChoiceDetails(String)` constructor that takes
     * a Play-shaped JSON payload. The Play SDK uses this same constructor
     * internally when wrapping the IPC callback — round-tripping the JSON
     * exercises the real parser.
     *
     * NOTE: If a Play Billing upgrade changes the JSON shape and these tests
     * suddenly see empty products / null tokens, inspect the current
     * `UserChoiceDetails` + `UserChoiceDetails$Product` source for the new
     * field names. Today (PBL 7.1.1) those are `externalTransactionToken`,
     * `originalExternalTransactionId`, and per-product
     * `productId` / `productType` / `offerToken`.
     */
    private fun fakeDetails(
        token: String,
        productIds: List<String>,
    ): UserChoiceDetails {
        val ctor = UserChoiceDetails::class.java.getDeclaredConstructor(String::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(buildJsonString(token, productIds))
    }

    private fun buildJsonString(token: String, productIds: List<String>): String {
        val products = productIds.joinToString(",") { """{"productId":"$it","offerToken":"o-$it","productType":"inapp"}""" }
        return """
            {
              "externalTransactionToken": "$token",
              "originalExternalTransactionId": "txn-orig-1",
              "products": [$products]
            }
        """.trimIndent()
    }

    @Test fun userSelectedAlternativeBilling_withValidDetails_callsLauncher() = runTest {
        val launcher = RecordingLauncher()
        val handler = UcbChoiceHandler(
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            logger = logger,
            launcher = launcher,
            userIdProvider = { "user-42" },
        )

        handler.userSelectedAlternativeBilling(fakeDetails("tok-abc", listOf("pro_monthly")))
        advanceUntilIdle()

        assertThat(launcher.calls).containsExactly(
            RecordingLauncher.Call(token = "tok-abc", productId = "pro_monthly", userId = "user-42"),
        )
    }

    @Test fun userSelectedAlternativeBilling_emptyToken_dropsCallback() = runTest {
        val launcher = RecordingLauncher()
        val handler = UcbChoiceHandler(
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            logger = logger,
            launcher = launcher,
            userIdProvider = { "user-42" },
        )

        handler.userSelectedAlternativeBilling(fakeDetails("", listOf("pro_monthly")))
        advanceUntilIdle()

        assertThat(launcher.calls).isEmpty()
    }

    @Test fun userSelectedAlternativeBilling_emptyProductList_dropsCallback() = runTest {
        val launcher = RecordingLauncher()
        val handler = UcbChoiceHandler(
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            logger = logger,
            launcher = launcher,
            userIdProvider = { "user-42" },
        )

        handler.userSelectedAlternativeBilling(fakeDetails("tok-abc", emptyList()))
        advanceUntilIdle()

        assertThat(launcher.calls).isEmpty()
    }

    @Test fun userSelectedAlternativeBilling_nullUserId_dropsCallback() = runTest {
        val launcher = RecordingLauncher()
        val handler = UcbChoiceHandler(
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            logger = logger,
            launcher = launcher,
            userIdProvider = { null },
        )

        handler.userSelectedAlternativeBilling(fakeDetails("tok-abc", listOf("pro_monthly")))
        advanceUntilIdle()

        assertThat(launcher.calls).isEmpty()
    }
}
