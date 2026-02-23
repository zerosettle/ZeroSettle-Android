package com.zerosettle.sdk.ui

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.core.ZSLogger
import com.zerosettle.sdk.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

/**
 * Activity that presents the upgrade offer as a bottom sheet card.
 * Uses Android Views (matching CancelFlowActivity pattern).
 * Communicates result back via [ZeroSettle.upgradeOfferDeferred].
 *
 * Flow: Current plan info -> Target plan with savings -> CTA / Dismiss
 */
class UpgradeOfferActivity : Activity() {

    companion object {
        const val EXTRA_CONFIG_JSON = "com.zerosettle.sdk.UPGRADE_OFFER_CONFIG"
        const val EXTRA_USER_ID = "com.zerosettle.sdk.UPGRADE_OFFER_USER_ID"

        private const val SHEET_HEIGHT_PERCENT = 0.75f
        private const val CORNER_RADIUS_DP = 20f
        private const val HANDLE_WIDTH_DP = 40f
        private const val HANDLE_HEIGHT_DP = 4f
        private const val HANDLE_MARGIN_TOP_DP = 10f
        private const val HANDLE_MARGIN_BOTTOM_DP = 8f
        private const val SCRIM_COLOR = 0x80000000.toInt()
        private const val ANIM_DURATION_MS = 350L
        private const val GREEN_COLOR = 0xFF4CAF50.toInt()
        private const val BADGE_GREEN_COLOR = 0xFF2E7D32.toInt()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun createIntent(
            context: Context,
            configJson: String,
            userId: String,
        ): Intent {
            return Intent(context, UpgradeOfferActivity::class.java).apply {
                putExtra(EXTRA_CONFIG_JSON, configJson)
                putExtra(EXTRA_USER_ID, userId)
            }
        }
    }

    private var sheetContainer: View? = null
    private var scrimView: View? = null
    private var ctaButton: TextView? = null
    private var hasCompleted = false

    private lateinit var config: UpgradeOfferConfig
    private lateinit var userId: String

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
        userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""

        if (configJson == null) {
            finishWithResult(UpgradeOfferResult.Dismissed)
            return
        }

        try {
            config = json.decodeFromString(UpgradeOfferConfig.serializer(), configJson)
        } catch (e: Exception) {
            ZSLogger.error("Failed to parse upgrade offer config: $e", ZSLogger.Category.IAP)
            finishWithResult(UpgradeOfferResult.Dismissed)
            return
        }

        val density = resources.displayMetrics.density
        val screenHeight = resources.displayMetrics.heightPixels
        val sheetHeight = (screenHeight * SHEET_HEIGHT_PERCENT).toInt()
        val sheetCornerRadius = CORNER_RADIUS_DP * density

        // Root container
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // Scrim
        val scrim = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(SCRIM_COLOR)
            alpha = 0f
            setOnClickListener { finishWithResult(UpgradeOfferResult.Dismissed) }
        }
        this.scrimView = scrim
        root.addView(scrim)

        // Sheet card
        val sheetBackground = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(
                sheetCornerRadius, sheetCornerRadius,
                sheetCornerRadius, sheetCornerRadius,
                0f, 0f, 0f, 0f,
            )
        }

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                sheetHeight,
            ).apply { gravity = Gravity.BOTTOM }
            background = sheetBackground
            elevation = 16f * density
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + sheetCornerRadius.toInt(), sheetCornerRadius)
                }
            }
        }
        this.sheetContainer = sheet

        // Drag handle
        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (HANDLE_WIDTH_DP * density).toInt(),
                (HANDLE_HEIGHT_DP * density).toInt(),
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (HANDLE_MARGIN_TOP_DP * density).toInt()
                bottomMargin = (HANDLE_MARGIN_BOTTOM_DP * density).toInt()
            }
            background = GradientDrawable().apply {
                setColor(0xFFDDDDDD.toInt())
                cornerRadius = (HANDLE_HEIGHT_DP * density) / 2f
            }
        }
        sheet.addView(handle)

        // Header: close button
        val header = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }

        val closeButton = TextView(this).apply {
            text = "\u2715"
            textSize = 18f
            setTextColor(0xFF999999.toInt())
            gravity = Gravity.CENTER
            val size = (32 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            setOnClickListener { finishWithResult(UpgradeOfferResult.Dismissed) }
        }
        header.addView(closeButton)

        sheet.addView(header)

        // Scrollable content area
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val content = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            val pad = (20 * density).toInt()
            setPadding(pad, 0, pad, 0)
        }

        renderOfferContent(content, density)
        scrollView.addView(content)
        sheet.addView(scrollView)

        // Bottom buttons
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            val pad = (20 * density).toInt()
            setPadding(pad, (12 * density).toInt(), pad, (20 * density).toInt())
        }

        val ctaText = config.display?.ctaText ?: "Upgrade Now"
        val cta = TextView(this).apply {
            text = ctaText
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val pad = (14 * density).toInt()
            setPadding(0, pad, 0, pad)
            background = GradientDrawable().apply {
                setColor(GREEN_COLOR)
                cornerRadius = 12 * density
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { onUpgradeTap() }
        }
        this.ctaButton = cta
        buttonContainer.addView(cta)

        val dismissText = config.display?.dismissText ?: "No thanks"
        val dismiss = TextView(this).apply {
            text = dismissText
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            gravity = Gravity.CENTER
            val topPad = (12 * density).toInt()
            setPadding(0, topPad, 0, (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { finishWithResult(UpgradeOfferResult.Declined) }
        }
        buttonContainer.addView(dismiss)

        sheet.addView(buttonContainer)
        root.addView(sheet)
        setContentView(root)

        // Animate in
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

    // -- Rendering --

    private fun renderOfferContent(container: FrameLayout, density: Float) {
        val display = config.display
        val currentProduct = config.currentProduct
        val targetProduct = config.targetProduct
        val savingsPercent = config.savingsPercent
        val proration = config.proration

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // Top spacer
        val topSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (16 * density).toInt(),
            )
        }
        layout.addView(topSpacer)

        // Icon
        val icon = TextView(this).apply {
            text = "\u2B06\uFE0F"
            textSize = 44f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        layout.addView(icon)

        // Title
        if (display != null) {
            val spacer1 = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (16 * density).toInt(),
                )
            }
            layout.addView(spacer1)

            val titleView = TextView(this).apply {
                text = display.title
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            layout.addView(titleView)

            val spacer2 = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (8 * density).toInt(),
                )
            }
            layout.addView(spacer2)

            val bodyText = if (config.upgradeType == UpgradeOfferType.STOREKIT_TO_WEB && display.storekitMigrationBody != null) {
                display.storekitMigrationBody
            } else {
                display.body
            }

            val bodyView = TextView(this).apply {
                text = bodyText
                textSize = 16f
                setTextColor(0xFF666666.toInt())
                gravity = Gravity.CENTER
                maxLines = 10
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            layout.addView(bodyView)
        }

        // Savings badge
        if (savingsPercent != null && savingsPercent > 0) {
            val badgeSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (16 * density).toInt(),
                )
            }
            layout.addView(badgeSpacer)

            val badge = TextView(this).apply {
                text = "Save $savingsPercent%"
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val hPad = (16 * density).toInt()
                val vPad = (6 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                background = GradientDrawable().apply {
                    setColor(BADGE_GREEN_COLOR)
                    cornerRadius = 16 * density
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            }
            layout.addView(badge)
        }

        // Plan comparison section
        if (currentProduct != null && targetProduct != null) {
            val comparisonSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (24 * density).toInt(),
                )
            }
            layout.addView(comparisonSpacer)

            // Current plan card
            layout.addView(buildPlanCard(
                label = "Current Plan",
                name = currentProduct.name,
                billing = currentProduct.billingLabel,
                priceCents = currentProduct.priceCents,
                currency = currentProduct.currency,
                density = density,
                isHighlighted = false,
            ))

            // Arrow between plans
            val arrow = TextView(this).apply {
                text = "\u2193"
                textSize = 24f
                setTextColor(0xFF999999.toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (8 * density).toInt()
                    bottomMargin = (8 * density).toInt()
                }
            }
            layout.addView(arrow)

            // Target plan card
            layout.addView(buildPlanCard(
                label = "Upgrade To",
                name = targetProduct.name,
                billing = targetProduct.billingLabel,
                priceCents = targetProduct.priceCents,
                currency = targetProduct.currency,
                density = density,
                isHighlighted = true,
            ))
        }

        // Proration info
        if (proration != null) {
            val prorationSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (16 * density).toInt(),
                )
            }
            layout.addView(prorationSpacer)

            val prorationText = formatProrationText(proration)
            val prorationView = TextView(this).apply {
                text = prorationText
                textSize = 14f
                setTextColor(0xFF666666.toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            layout.addView(prorationView)
        }

        // StoreKit migration cancel instructions
        if (config.upgradeType == UpgradeOfferType.STOREKIT_TO_WEB && display?.cancelInstructions != null) {
            val instructionsSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (16 * density).toInt(),
                )
            }
            layout.addView(instructionsSpacer)

            val instructionsView = TextView(this).apply {
                text = display.cancelInstructions
                textSize = 13f
                setTextColor(0xFF999999.toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(0xFFFFF8E1.toInt())
                    cornerRadius = 8 * density
                }
            }
            layout.addView(instructionsView)
        }

        container.addView(layout)
    }

    private fun buildPlanCard(
        label: String,
        name: String,
        billing: String,
        priceCents: Int,
        currency: String,
        density: Float,
        isHighlighted: Boolean,
    ): LinearLayout {
        val cardBackground = if (isHighlighted) 0xFFF1F8E9.toInt() else 0xFFF5F5F5.toInt()
        val borderColor = if (isHighlighted) GREEN_COLOR else 0xFFE0E0E0.toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(cardBackground)
                setStroke((if (isHighlighted) 2 else 1) * density.toInt(), borderColor)
                cornerRadius = 12 * density
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )

            // Label
            val labelView = TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(if (isHighlighted) BADGE_GREEN_COLOR else 0xFF999999.toInt())
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            addView(labelView)

            // Product name
            val nameView = TextView(context).apply {
                text = name
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (4 * density).toInt() }
            }
            addView(nameView)

            // Price + billing label
            val priceRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (4 * density).toInt() }
            }

            val priceView = TextView(context).apply {
                text = formatPrice(priceCents, currency)
                textSize = 16f
                setTextColor(if (isHighlighted) BADGE_GREEN_COLOR else 0xFF666666.toInt())
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            priceRow.addView(priceView)

            val billingView = TextView(context).apply {
                text = " / $billing"
                textSize = 14f
                setTextColor(0xFF999999.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            priceRow.addView(billingView)

            addView(priceRow)
        }
    }

    private fun formatPrice(cents: Int, currency: String): String {
        val dollars = cents / 100.0
        val symbol = when (currency.uppercase()) {
            "USD" -> "$"
            "EUR" -> "\u20AC"
            "GBP" -> "\u00A3"
            else -> "$currency "
        }
        return "$symbol%.2f".format(dollars)
    }

    private fun formatProrationText(proration: UpgradeOfferProration): String {
        val amount = formatPrice(proration.prorationAmountCents, proration.currency)
        return "You'll be charged $amount today (prorated for your current billing period)."
    }

    // -- Button Handlers --

    private fun onUpgradeTap() {
        val currentProduct = config.currentProduct ?: return
        val targetProduct = config.targetProduct ?: return

        // Disable the button to prevent double-taps
        ctaButton?.apply {
            alpha = 0.4f
            isClickable = false
        }

        scope.launch {
            try {
                val backend = ZeroSettle.effectiveBaseUrl?.let { baseUrl ->
                    ZeroSettle.currentConfig?.let { sdkConfig ->
                        com.zerosettle.sdk.internal.Backend(baseUrl, sdkConfig.publishableKey)
                    }
                }

                if (backend != null) {
                    backend.executeUpgrade(
                        currentProductId = currentProduct.referenceId,
                        targetProductId = targetProduct.referenceId,
                        userId = userId,
                    )
                }

                finishWithResult(UpgradeOfferResult.Upgraded)
            } catch (e: Exception) {
                ZSLogger.error("Failed to execute upgrade: $e", ZSLogger.Category.IAP)
                // Re-enable the button so the user can retry
                ctaButton?.apply {
                    alpha = 1f
                    isClickable = true
                }
            }
        }
    }

    // -- Results --

    private fun finishWithResult(result: UpgradeOfferResult) {
        if (hasCompleted) return
        hasCompleted = true

        ZeroSettle.upgradeOfferDeferred?.complete(result)

        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        if (!hasCompleted) {
            ZeroSettle.upgradeOfferDeferred?.complete(UpgradeOfferResult.Dismissed)
        }
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Use onBackPressedDispatcher", ReplaceWith("onBackPressedDispatcher"))
    override fun onBackPressed() {
        if (!hasCompleted) {
            finishWithResult(UpgradeOfferResult.Dismissed)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun finish() {
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
}
