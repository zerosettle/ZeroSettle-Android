package com.zerosettle.sdk.billing

import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.AlternativeBillingOnlyAvailabilityListener
import com.android.billingclient.api.AlternativeBillingOnlyInformationDialogListener
import com.android.billingclient.api.AlternativeBillingOnlyReportingDetailsListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingConfigResponseListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingProgramAvailabilityListener
import com.android.billingclient.api.BillingProgramReportingDetailsListener
import com.android.billingclient.api.BillingProgramReportingDetailsParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ExternalOfferAvailabilityListener
import com.android.billingclient.api.ExternalOfferInformationDialogListener
import com.android.billingclient.api.ExternalOfferReportingDetailsListener
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResponseListener
import com.android.billingclient.api.LaunchExternalLinkParams
import com.android.billingclient.api.LaunchExternalLinkResponseListener
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ExternalContentLinkClient contract (Switch & Save — ECL, Tasks 2 & 3).
 *
 * The client wraps:
 *  - [BillingClient.isBillingProgramAvailableAsync] — surfaced via [ExternalContentLinkClient.isAvailable]
 *  - [BillingClient.createBillingProgramReportingDetailsAsync] — surfaced via
 *    [ExternalContentLinkClient.newTransactionToken]
 *
 * Both entry points share the same connection lifecycle (connect once per ECL session).
 *
 * Because Robolectric cannot provision real Play Services, and BillingClient
 * is abstract with many methods, we use a fake BillingClient subclass that:
 *  - [FakeBillingClient.startConnection] — fires [BillingClientStateListener.onBillingSetupFinished]
 *    synchronously with the configured connection response code (OK by default).
 *  - [FakeBillingClient.isBillingProgramAvailableAsync] — fires the listener
 *    synchronously with the configured availability response code.
 *  - [FakeBillingClient.createBillingProgramReportingDetailsAsync] — fires the listener
 *    synchronously with the configured reporting response code and token.
 *  - All other abstract methods throw [UnsupportedOperationException] (they
 *    should never be reached by [ExternalContentLinkClient]).
 *
 * We pass a pre-built fake client via the test-seam constructor so the
 * production BillingClient.newBuilder() path is not exercised in tests.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalContentLinkClientTest {

    // ─── Fake BillingClient ────────────────────────────────────────────────

    /**
     * Minimal BillingClient subclass that drives [ExternalContentLinkClient]
     * through the exact async callbacks it relies on.
     *
     * [availabilityResponseCode] controls what [isBillingProgramAvailableAsync] fires.
     * [reportingResponseCode] controls what [createBillingProgramReportingDetailsAsync] fires.
     * [reportingToken] is the [externalTransactionToken] returned with a reporting OK response.
     * [connectionResponseCode] controls what [startConnection] fires (defaults to OK
     * so the connect step is transparent unless a test wants a connection failure).
     *
     * [BillingProgramAvailabilityDetails] and [BillingProgramReportingDetails] both
     * have package-private constructors in PBL 8.2.1, so we build them via reflection.
     * The production code only reads [BillingResult.responseCode] for availability, and
     * [BillingProgramReportingDetails.externalTransactionToken] for the reporting token.
     */
    private class FakeBillingClient(
        private val availabilityResponseCode: Int,
        private val reportingResponseCode: Int = BillingResponseCode.OK,
        private val reportingToken: String = "",
        private val connectionResponseCode: Int = BillingResponseCode.OK,
    ) : BillingClient() {

        override fun isReady(): Boolean = false

        override fun getConnectionState(): Int = ConnectionState.DISCONNECTED

        override fun startConnection(listener: BillingClientStateListener) {
            listener.onBillingSetupFinished(
                BillingResult.newBuilder()
                    .setResponseCode(connectionResponseCode)
                    .build(),
            )
        }

        override fun isBillingProgramAvailableAsync(
            billingProgram: Int,
            listener: BillingProgramAvailabilityListener,
        ) {
            // BillingProgramAvailabilityDetails has a package-private constructor in PBL 8.2.1,
            // so we construct it via reflection. The production code only reads BillingResult.responseCode
            // and never touches the details object — this is purely to satisfy the non-null parameter.
            val detailsCtor = com.android.billingclient.api.BillingProgramAvailabilityDetails::class.java
                .getDeclaredConstructor(Int::class.java)
            detailsCtor.isAccessible = true
            val details = detailsCtor.newInstance(billingProgram)
            listener.onBillingProgramAvailabilityResponse(
                BillingResult.newBuilder()
                    .setResponseCode(availabilityResponseCode)
                    .build(),
                details,
            )
        }

        override fun endConnection() { /* no-op */ }

        // ── Unused abstract methods — not reachable from ExternalContentLinkClient ──

        override fun isFeatureSupported(feature: String): BillingResult =
            throw UnsupportedOperationException("not used by ExternalContentLinkClient")

        override fun launchBillingFlow(
            activity: android.app.Activity,
            params: BillingFlowParams,
        ): BillingResult = throw UnsupportedOperationException()

        override fun showAlternativeBillingOnlyInformationDialog(
            activity: android.app.Activity,
            listener: AlternativeBillingOnlyInformationDialogListener,
        ): BillingResult = throw UnsupportedOperationException()

        override fun showExternalOfferInformationDialog(
            activity: android.app.Activity,
            listener: ExternalOfferInformationDialogListener,
        ): BillingResult = throw UnsupportedOperationException()

        override fun showInAppMessages(
            activity: android.app.Activity,
            params: InAppMessageParams,
            listener: InAppMessageResponseListener,
        ): BillingResult = throw UnsupportedOperationException()

        override fun acknowledgePurchase(
            params: AcknowledgePurchaseParams,
            listener: AcknowledgePurchaseResponseListener,
        ) = throw UnsupportedOperationException()

        override fun consumeAsync(
            params: ConsumeParams,
            listener: ConsumeResponseListener,
        ) = throw UnsupportedOperationException()

        override fun createAlternativeBillingOnlyReportingDetailsAsync(
            listener: AlternativeBillingOnlyReportingDetailsListener,
        ) = throw UnsupportedOperationException()

        override fun createBillingProgramReportingDetailsAsync(
            params: BillingProgramReportingDetailsParams,
            listener: BillingProgramReportingDetailsListener,
        ) {
            // BillingProgramReportingDetails(String externalTransactionToken, int billingProgram)
            // has a package-private constructor in PBL 8.2.1; use reflection to construct it.
            val detailsCtor = com.android.billingclient.api.BillingProgramReportingDetails::class.java
                .getDeclaredConstructor(String::class.java, Int::class.java)
            detailsCtor.isAccessible = true
            val details = detailsCtor.newInstance(reportingToken, params.billingProgram)
            listener.onCreateBillingProgramReportingDetailsResponse(
                BillingResult.newBuilder()
                    .setResponseCode(reportingResponseCode)
                    .build(),
                details,
            )
        }

        override fun createExternalOfferReportingDetailsAsync(
            listener: ExternalOfferReportingDetailsListener,
        ) = throw UnsupportedOperationException()

        override fun getBillingConfigAsync(
            params: GetBillingConfigParams,
            listener: BillingConfigResponseListener,
        ) = throw UnsupportedOperationException()

        override fun isAlternativeBillingOnlyAvailableAsync(
            listener: AlternativeBillingOnlyAvailabilityListener,
        ) = throw UnsupportedOperationException()

        override fun isExternalOfferAvailableAsync(
            listener: ExternalOfferAvailabilityListener,
        ) = throw UnsupportedOperationException()

        override fun launchExternalLink(
            activity: android.app.Activity,
            params: LaunchExternalLinkParams,
            listener: LaunchExternalLinkResponseListener,
        ) = throw UnsupportedOperationException()

        override fun queryProductDetailsAsync(
            params: QueryProductDetailsParams,
            listener: ProductDetailsResponseListener,
        ) = throw UnsupportedOperationException()

        override fun queryPurchasesAsync(
            params: QueryPurchasesParams,
            listener: PurchasesResponseListener,
        ) = throw UnsupportedOperationException()
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test fun isAvailable_withOkResponseCode_returnsTrue() = runTest {
        val client = ExternalContentLinkClient(FakeBillingClient(BillingClient.BillingResponseCode.OK))
        assertThat(client.isAvailable()).isTrue()
    }

    @Test fun isAvailable_withFeatureNotSupported_returnsFalse() = runTest {
        val client = ExternalContentLinkClient(
            FakeBillingClient(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED),
        )
        assertThat(client.isAvailable()).isFalse()
    }

    @Test fun isAvailable_withServiceUnavailable_returnsFalse() = runTest {
        val client = ExternalContentLinkClient(
            FakeBillingClient(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE),
        )
        assertThat(client.isAvailable()).isFalse()
    }

    @Test fun isAvailable_withBillingUnavailable_returnsFalse() = runTest {
        val client = ExternalContentLinkClient(
            FakeBillingClient(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE),
        )
        assertThat(client.isAvailable()).isFalse()
    }

    @Test fun isAvailable_whenConnectionFails_returnsFalse() = runTest {
        // The billing connection itself fails (onBillingSetupFinished fires a
        // non-OK code). isAvailable() must short-circuit to false WITHOUT
        // propagating a billing error — the ECL offer is simply suppressed.
        // The availability response code is irrelevant here: the check never runs.
        val client = ExternalContentLinkClient(
            FakeBillingClient(
                availabilityResponseCode = BillingClient.BillingResponseCode.OK,
                connectionResponseCode = BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            ),
        )
        assertThat(client.isAvailable()).isFalse()
    }

    // ─── newTransactionToken() tests (Task 3) ─────────────────────────────────

    @Test fun newTransactionToken_withOkResponse_returnsSuccessWithToken() = runTest {
        val client = ExternalContentLinkClient(
            FakeBillingClient(
                availabilityResponseCode = BillingClient.BillingResponseCode.OK,
                reportingResponseCode = BillingClient.BillingResponseCode.OK,
                reportingToken = "etx_test",
            ),
        )
        val result = client.newTransactionToken()
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("etx_test")
    }

    @Test fun newTransactionToken_withNonOkResponse_returnsFailure() = runTest {
        val client = ExternalContentLinkClient(
            FakeBillingClient(
                availabilityResponseCode = BillingClient.BillingResponseCode.OK,
                reportingResponseCode = BillingClient.BillingResponseCode.DEVELOPER_ERROR,
                reportingToken = "",
            ),
        )
        val result = client.newTransactionToken()
        assertThat(result.isFailure).isTrue()
    }

    @Test fun newTransactionToken_whenConnectionFails_returnsFailure() = runTest {
        val client = ExternalContentLinkClient(
            FakeBillingClient(
                availabilityResponseCode = BillingClient.BillingResponseCode.OK,
                reportingResponseCode = BillingClient.BillingResponseCode.OK,
                reportingToken = "etx_test",
                connectionResponseCode = BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            ),
        )
        val result = client.newTransactionToken()
        assertThat(result.isFailure).isTrue()
    }
}
