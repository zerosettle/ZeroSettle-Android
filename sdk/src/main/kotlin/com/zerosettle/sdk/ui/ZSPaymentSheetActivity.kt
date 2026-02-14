package com.zerosettle.sdk.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.core.ZSLogger
import com.zerosettle.sdk.error.PaymentFailureDetail
import com.zerosettle.sdk.error.PaymentSheetError
import com.zerosettle.sdk.internal.Backend
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Activity hosting an Android WebView for embedded Stripe checkout,
 * presented as a bottom sheet card over the calling activity.
 * Maps to iOS `ZSPaymentSheet` (the WebView path).
 */
class ZSPaymentSheetActivity : Activity() {

    companion object {
        const val EXTRA_PRODUCT_ID = "com.zerosettle.sdk.PRODUCT_ID"
        const val EXTRA_USER_ID = "com.zerosettle.sdk.USER_ID"
        const val EXTRA_CHECKOUT_URL = "com.zerosettle.sdk.CHECKOUT_URL"
        const val EXTRA_TRANSACTION_ID = "com.zerosettle.sdk.TRANSACTION_ID"
        const val EXTRA_FREE_TRIAL_DAYS = "com.zerosettle.sdk.FREE_TRIAL_DAYS"

        const val RESULT_CODE_SUCCESS = Activity.RESULT_OK
        const val RESULT_CODE_CANCELLED = Activity.RESULT_CANCELED
        const val RESULT_CODE_ERROR = 2

        private const val SHEET_HEIGHT_PERCENT = 0.92f
        private const val CORNER_RADIUS_DP = 20f
        private const val HANDLE_WIDTH_DP = 40f
        private const val HANDLE_HEIGHT_DP = 4f
        private const val HANDLE_MARGIN_TOP_DP = 10f
        private const val HANDLE_MARGIN_BOTTOM_DP = 8f
        private const val SCRIM_COLOR = 0x80000000.toInt()
        private const val ANIM_DURATION_MS = 350L

        private val CALLBACK_HOSTS = setOf(
            "api.zerosettle.io",
            "zerosettle.io",
            "landing.zerosettle.ngrok.app",
            "api.zerosettle.ngrok.app",
        )
        private const val CALLBACK_PATH_PREFIX = "/checkout/callback"

        fun createIntent(
            context: Context,
            productId: String,
            userId: String?,
            checkoutUrl: String,
            transactionId: String?,
            freeTrialDays: Int = 0,
        ): Intent {
            return Intent(context, ZSPaymentSheetActivity::class.java).apply {
                putExtra(EXTRA_PRODUCT_ID, productId)
                putExtra(EXTRA_USER_ID, userId)
                putExtra(EXTRA_CHECKOUT_URL, checkoutUrl)
                putExtra(EXTRA_TRANSACTION_ID, transactionId)
                putExtra(EXTRA_FREE_TRIAL_DAYS, freeTrialDays)
            }
        }
    }

    private var webView: WebView? = null
    private var sheetContainer: View? = null
    private var scrimView: View? = null
    private var transactionId: String? = null
    private var hasCompleted = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val checkoutUrl = intent.getStringExtra(EXTRA_CHECKOUT_URL)
        transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID)

        if (checkoutUrl == null) {
            finishWithError("cancelled", "No checkout URL provided")
            return
        }

        val density = resources.displayMetrics.density
        val screenHeight = resources.displayMetrics.heightPixels
        val sheetHeight = (screenHeight * SHEET_HEIGHT_PERCENT).toInt()
        val sheetCornerRadius = CORNER_RADIUS_DP * density

        // Root container (full screen, transparent)
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // Scrim background (semi-transparent, tappable to dismiss)
        val scrim = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(SCRIM_COLOR)
            alpha = 0f
            setOnClickListener {
                if (!hasCompleted) finishWithCancelled()
            }
        }
        this.scrimView = scrim
        root.addView(scrim)

        // Sheet card container (anchored to bottom, rounded top corners)
        val sheetBackground = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(
                sheetCornerRadius, sheetCornerRadius,   // top-left
                sheetCornerRadius, sheetCornerRadius,   // top-right
                0f, 0f,                                 // bottom-right
                0f, 0f,                                 // bottom-left
            )
        }

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                sheetHeight,
            ).apply {
                gravity = Gravity.BOTTOM
            }
            background = sheetBackground
            elevation = 16f * density
            // Clip children to the rounded shape
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + sheetCornerRadius.toInt(), sheetCornerRadius)
                }
            }
        }
        this.sheetContainer = sheet

        // Drag handle
        val handleMarginTop = (HANDLE_MARGIN_TOP_DP * density).toInt()
        val handleMarginBottom = (HANDLE_MARGIN_BOTTOM_DP * density).toInt()
        val handleWidth = (HANDLE_WIDTH_DP * density).toInt()
        val handleHeight = (HANDLE_HEIGHT_DP * density).toInt()

        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(handleWidth, handleHeight).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = handleMarginTop
                bottomMargin = handleMarginBottom
            }
            background = GradientDrawable().apply {
                setColor(0xFFDDDDDD.toInt())
                cornerRadius = handleHeight / 2f
            }
        }
        sheet.addView(handle)

        // WebView
        val wv = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f, // fill remaining space
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = true

            addJavascriptInterface(CheckoutBridge(), "ZeroSettleAndroid")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url ?: return false
                    if (isCallbackUrl(url)) {
                        handleCallbackUrl(url)
                        return true
                    }
                    return false
                }
            }

            setBackgroundColor(Color.WHITE)
            loadUrl(checkoutUrl)
        }
        this.webView = wv
        sheet.addView(wv)

        root.addView(sheet)
        setContentView(root)

        // Animate in: scrim fades, sheet slides up
        sheet.translationY = sheetHeight.toFloat()
        sheet.post {
            ObjectAnimator.ofFloat(sheet, "translationY", sheetHeight.toFloat(), 0f).apply {
                duration = ANIM_DURATION_MS
                interpolator = DecelerateInterpolator(1.5f)
                start()
            }
            ObjectAnimator.ofFloat(scrim, "alpha", 0f, 1f).apply {
                duration = ANIM_DURATION_MS
                start()
            }
        }
    }

    override fun onDestroy() {
        // Safety net: if the activity is destroyed without completing, cancel the deferred
        if (!hasCompleted) {
            ZeroSettle.paymentSheetDeferred?.completeExceptionally(PaymentSheetError.Cancelled)
        }
        scope.cancel()
        webView?.destroy()
        super.onDestroy()
    }

    @Deprecated("Use onBackPressedDispatcher", ReplaceWith("onBackPressedDispatcher"))
    override fun onBackPressed() {
        if (!hasCompleted) {
            finishWithCancelled()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun finish() {
        // Animate out: sheet slides down, scrim fades, then actually finish
        val sheet = sheetContainer
        val scrim = scrimView
        if (sheet != null && sheet.translationY == 0f) {
            val screenHeight = resources.displayMetrics.heightPixels.toFloat()
            ObjectAnimator.ofFloat(sheet, "translationY", 0f, screenHeight).apply {
                duration = ANIM_DURATION_MS
                interpolator = DecelerateInterpolator(1.5f)
                start()
            }
            scrim?.let {
                ObjectAnimator.ofFloat(it, "alpha", 1f, 0f).apply {
                    duration = ANIM_DURATION_MS
                    start()
                }
            }
            sheet.postDelayed({ super.finish(); overridePendingTransition(0, 0) }, ANIM_DURATION_MS)
        } else {
            super.finish()
            overridePendingTransition(0, 0)
        }
    }

    // -- JavaScript Interface --
    // Receives JSON messages from the native checkout page's postToNative() function.
    // Message format: { action: "ready"|"complete"|"error"|"expandSheet"|"collapseSheet",
    //                   success?: bool, transaction_id?: string, message?: string }

    inner class CheckoutBridge {
        @JavascriptInterface
        fun onNativeMessage(json: String) {
            runOnUiThread {
                try {
                    val data = JSONObject(json)
                    val action = data.optString("action", "")
                    ZSLogger.debug("Payment sheet message: $action", ZSLogger.Category.IAP)

                    when (action) {
                        "ready" -> {
                            // Page loaded successfully — nothing to do
                        }
                        "complete" -> {
                            if (hasCompleted) return@runOnUiThread
                            hasCompleted = true
                            val success = data.optBoolean("success", false)
                            val txnId = data.optString("transaction_id", "")
                            if (success && txnId.isNotEmpty()) {
                                verifyAndComplete(txnId)
                            } else {
                                finishWithError("payment_failed", "Payment failed")
                            }
                        }
                        "error" -> {
                            if (hasCompleted) return@runOnUiThread
                            hasCompleted = true
                            val message = data.optString("message", "Checkout error")
                            finishWithError("checkout_error", message)
                        }
                        "expandSheet", "collapseSheet", "contentHeight" -> {
                            // UI hints — no action needed on Android
                        }
                        else -> {
                            // Legacy fallback: treat unknown messages with success/transaction_id as complete
                            if (hasCompleted) return@runOnUiThread
                            val success = data.optBoolean("success", false)
                            val txnId = data.optString("transaction_id", "")
                            if (success && txnId.isNotEmpty()) {
                                hasCompleted = true
                                verifyAndComplete(txnId)
                            } else if (data.optBoolean("cancelled", false)) {
                                hasCompleted = true
                                finishWithCancelled()
                            }
                        }
                    }
                } catch (e: Exception) {
                    ZSLogger.error("Failed to parse native message: $e", ZSLogger.Category.IAP)
                }
            }
        }
    }

    // -- Callback URL Handling --

    private fun isCallbackUrl(url: Uri): Boolean {
        val host = url.host ?: return false
        val path = url.path ?: return false
        return host in CALLBACK_HOSTS && path.startsWith(CALLBACK_PATH_PREFIX)
    }

    private fun handleCallbackUrl(url: Uri) {
        if (hasCompleted) return
        hasCompleted = true

        val txnId = url.getQueryParameter("transaction_id")
        val status = url.getQueryParameter("status")

        if (txnId != null && (status == "success" || status == "processing")) {
            verifyAndComplete(txnId)
        } else {
            finishWithCancelled()
        }
    }

    // -- Verification --

    private fun verifyAndComplete(txnId: String) {
        val baseUrl = ZeroSettle.effectiveBaseUrl ?: run {
            finishWithError("not_configured", "ZeroSettle is not configured")
            return
        }
        val config = ZeroSettle.currentConfig ?: run {
            finishWithError("not_configured", "ZeroSettle is not configured")
            return
        }

        scope.launch {
            val backend = Backend(baseUrl = baseUrl, publishableKey = config.publishableKey)
            try {
                val transaction = backend.verifyTransaction(transactionId = txnId)

                // Deliver result via deferred (purchaseViaPaymentSheet handles delegate calls)
                ZeroSettle.paymentSheetDeferred?.complete(transaction)

                setResult(RESULT_CODE_SUCCESS)
                finish()
            } catch (e: Exception) {
                finishWithError("verification_failed", e.message ?: "Verification failed")
            }
        }
    }

    // -- Result Helpers --

    private fun finishWithCancelled() {
        ZeroSettle.paymentSheetDeferred?.completeExceptionally(PaymentSheetError.Cancelled)
        setResult(RESULT_CODE_CANCELLED)
        finish()
    }

    private fun finishWithError(type: String, message: String) {
        val kind = when (type) {
            "payment_failed" -> PaymentFailureDetail.Kind.CARD_DECLINED
            "checkout_error" -> PaymentFailureDetail.Kind.CHECKOUT_ERROR
            "verification_failed" -> PaymentFailureDetail.Kind.SERVER_ERROR
            else -> PaymentFailureDetail.Kind.UNKNOWN
        }
        val error = PaymentSheetError.PaymentFailed(PaymentFailureDetail(kind = kind, message = message))
        ZeroSettle.paymentSheetDeferred?.completeExceptionally(error)
        setResult(RESULT_CODE_ERROR)
        finish()
    }
}
