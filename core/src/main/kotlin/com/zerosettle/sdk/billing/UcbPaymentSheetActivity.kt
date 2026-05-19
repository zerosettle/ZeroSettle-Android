package com.zerosettle.sdk.billing

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult

/**
 * Transparent host activity that presents Stripe's [PaymentSheet] for a User
 * Choice Billing alternative-payment checkout.
 *
 * The activity owns no UI of its own — PaymentSheet renders a bottom sheet
 * over whatever the host app was showing. We use [Theme.ZeroSettleSheet]
 * (same translucent theme as [com.zerosettle.sdk.checkout.ZeroSettleWebViewActivity])
 * so the host activity remains visible behind the sheet. The activity exists
 * solely to satisfy Stripe's contract: `PaymentSheet(ComponentActivity, …)`
 * needs an Activity / Fragment lifecycle to register its `ActivityResult`
 * contract in `onCreate` — neither is available to [StripeCheckoutLauncher]
 * which is constructed with an [android.content.Context].
 *
 * **Result delivery.** The activity routes [PaymentSheetResult] back to the
 * suspending caller via the process-static [UcbResultBridge]. The launcher
 * `reserve()`s the bridge before dispatching the launch Intent and awaits;
 * the activity `deliver()`s the outcome here. See [UcbResultBridge] for the
 * rationale (no Activity context at launch, `startActivityForResult`
 * deprecated, `ActivityResult` API requires an Activity / Fragment).
 *
 * **Why `ComponentActivity` and not `AppCompatActivity`?** The SDK's `:core`
 * module deliberately avoids AppCompat — same constraint that drove
 * `ZeroSettleWebViewActivity`'s `ComponentActivity` choice (see themes.xml).
 * `PaymentSheet`'s constructor accepts `ComponentActivity` so no AppCompat
 * dependency is needed.
 */
internal class UcbPaymentSheetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val clientSecret = intent.getStringExtra(EXTRA_CLIENT_SECRET)
        val publishableKey = intent.getStringExtra(EXTRA_PUBLISHABLE_KEY)
        if (clientSecret.isNullOrBlank() || publishableKey.isNullOrBlank()) {
            // Defensive: launcher should never dispatch without these. Don't
            // crash + don't leave the bridge hanging — deliver Failed so the
            // launcher's suspending await resumes.
            UcbResultBridge.deliver(
                PaymentSheetStatus.Failed(
                    "UcbPaymentSheetActivity launched without required extras (client_secret/publishable_key)",
                ),
            )
            finish()
            return
        }

        val stripeAccount = intent.getStringExtra(EXTRA_STRIPE_ACCOUNT).orEmpty()
        val merchantCountry = intent.getStringExtra(EXTRA_MERCHANT_COUNTRY).orEmpty()
        val merchantDisplayName = intent.getStringExtra(EXTRA_MERCHANT_DISPLAY_NAME).orEmpty()
        val isSandbox = intent.getBooleanExtra(EXTRA_IS_SANDBOX, false)

        // PaymentConfiguration is a process-singleton (`PaymentConfiguration.Store`)
        // owned by Stripe. Re-initialising on each presentation is supported and
        // matches Stripe's own samples — the connected-account id is what changes
        // per checkout for BYOS merchants.
        PaymentConfiguration.init(this, publishableKey, stripeAccount)

        val paymentSheet = PaymentSheet(this, ::onPaymentSheetResult)

        val googlePay = PaymentSheet.GooglePayConfiguration(
            environment = if (isSandbox) {
                PaymentSheet.GooglePayConfiguration.Environment.Test
            } else {
                PaymentSheet.GooglePayConfiguration.Environment.Production
            },
            countryCode = merchantCountry.ifEmpty { DEFAULT_COUNTRY_CODE },
            currencyCode = DEFAULT_CURRENCY_CODE,
        )

        val configuration = PaymentSheet.Configuration.Builder(
            merchantDisplayName = merchantDisplayName.ifEmpty { DEFAULT_MERCHANT_DISPLAY_NAME },
        )
            .googlePay(googlePay)
            // UCB checkouts must capture instantly — subscription renewals
            // can't tolerate delayed payment methods (would leave the buyer's
            // entitlement undecided across the renewal window).
            .allowsDelayedPaymentMethods(false)
            .build()

        paymentSheet.presentWithPaymentIntent(clientSecret, configuration)
    }

    private fun onPaymentSheetResult(result: PaymentSheetResult) {
        // The activity ONLY knows the PaymentSheet result; the
        // externalTransactionId + transactionRef were reserved on the bridge
        // by the launcher from the `/initiate/` response. The bridge composes
        // them into a final [UcbPurchaseOutcome] using the reserved IDs —
        // see [UcbResultBridge.deliver].
        val status = when (result) {
            is PaymentSheetResult.Completed -> PaymentSheetStatus.Completed
            is PaymentSheetResult.Canceled -> PaymentSheetStatus.Canceled
            is PaymentSheetResult.Failed -> PaymentSheetStatus.Failed(
                result.error.message ?: result.error::class.simpleName ?: "payment_failed",
            )
        }
        UcbResultBridge.deliver(status)
        finish()
    }

    internal companion object {
        const val EXTRA_CLIENT_SECRET: String = "zs.ucb.client_secret"
        const val EXTRA_STRIPE_ACCOUNT: String = "zs.ucb.stripe_account"
        const val EXTRA_MERCHANT_COUNTRY: String = "zs.ucb.merchant_country"
        const val EXTRA_PUBLISHABLE_KEY: String = "zs.ucb.publishable_key"
        const val EXTRA_IS_SANDBOX: String = "zs.ucb.is_sandbox"
        const val EXTRA_MERCHANT_DISPLAY_NAME: String = "zs.ucb.merchant_display_name"

        /**
         * Default Google Pay country code when the backend doesn't surface a
         * merchant country (platform-managed merchants without a Connect
         * account). Apple Pay's domain-registration audit caught the same
         * gotcha — see ../../../checkout/native-checkout.html. "US" is the
         * conservative safe default since the backend's catalog is USD-anchored
         * today; Chunk D may revisit if non-US merchants onboard.
         */
        private const val DEFAULT_COUNTRY_CODE: String = "US"

        /**
         * Default Google Pay currency code. Aligned with the backend's
         * USD-only price configuration. If multi-currency support lands,
         * surface `currency_code` from the initiate response and thread it
         * through `EXTRA_*`.
         */
        private const val DEFAULT_CURRENCY_CODE: String = "USD"

        private const val DEFAULT_MERCHANT_DISPLAY_NAME: String = "ZeroSettle"
    }
}
