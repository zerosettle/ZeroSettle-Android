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
 * Activity that presents the cancel flow questionnaire as a bottom sheet card.
 * Uses Android Views (matching CheckoutSheetActivity pattern).
 * Communicates result back via [ZeroSettle.cancelFlowDeferred].
 *
 * Flow: Questions -> Retention Page (offer + pause) -> Done
 */
class CancelFlowActivity : Activity() {

    companion object {
        const val EXTRA_CONFIG_JSON = "com.zerosettle.sdk.CANCEL_FLOW_CONFIG"
        const val EXTRA_PRODUCT_ID = "com.zerosettle.sdk.CANCEL_FLOW_PRODUCT_ID"
        const val EXTRA_USER_ID = "com.zerosettle.sdk.CANCEL_FLOW_USER_ID"

        private const val SHEET_HEIGHT_PERCENT = 0.85f
        private const val CORNER_RADIUS_DP = 20f
        private const val HANDLE_WIDTH_DP = 40f
        private const val HANDLE_HEIGHT_DP = 4f
        private const val HANDLE_MARGIN_TOP_DP = 10f
        private const val HANDLE_MARGIN_BOTTOM_DP = 8f
        private const val SCRIM_COLOR = 0x80000000.toInt()
        private const val ANIM_DURATION_MS = 350L
        private const val GREEN_COLOR = 0xFF4CAF50.toInt()
        private const val BLUE_COLOR = 0xFF2196F3.toInt()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun createIntent(
            context: Context,
            configJson: String,
            productId: String,
            userId: String,
        ): Intent {
            return Intent(context, CancelFlowActivity::class.java).apply {
                putExtra(EXTRA_CONFIG_JSON, configJson)
                putExtra(EXTRA_PRODUCT_ID, productId)
                putExtra(EXTRA_USER_ID, userId)
            }
        }
    }

    private var sheetContainer: View? = null
    private var scrimView: View? = null
    private var contentContainer: FrameLayout? = null
    private var primaryButton: TextView? = null
    private var secondaryButton: TextView? = null
    private var dotsContainer: LinearLayout? = null
    private var hasCompleted = false

    private lateinit var config: CancelFlowConfig
    private lateinit var productId: String
    private lateinit var userId: String

    private var currentQuestionIndex = 0
    private val answers = mutableListOf<AnswerData>()
    private var selectedOptionId: Int? = null
    private var freeTextInput = ""
    private var showingRetention = false
    private var offerShown = false
    private var pauseShown = false
    private var lastStepSeen = 0
    private var earlyOfferTriggered = false

    /** Currently selected pause option ID (on the retention page). */
    private var selectedPauseOptionId: Int? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private data class AnswerData(
        val questionId: Int,
        val selectedOptionId: Int?,
        val freeText: String?,
    )

    /** Whether the retention page has any content (offer or pause). */
    private val hasRetentionPage: Boolean
        get() = config.offer?.enabled == true || config.pause?.enabled == true

    private val totalSteps: Int
        get() {
            val retentionStep = if (hasRetentionPage) 1 else 0
            return config.questions.size + retentionStep
        }

    private val currentStep: Int
        get() = if (showingRetention) config.questions.size else currentQuestionIndex

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
        productId = intent.getStringExtra(EXTRA_PRODUCT_ID) ?: ""
        userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""

        if (configJson == null) {
            finishWithResult(CancelFlowResult.Dismissed)
            return
        }

        try {
            config = json.decodeFromString(CancelFlowConfig.serializer(), configJson)
        } catch (e: Exception) {
            ZSLogger.error("Failed to parse cancel flow config: $e", ZSLogger.Category.IAP)
            finishWithResult(CancelFlowResult.Dismissed)
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
            setOnClickListener { finishWithResult(CancelFlowResult.Dismissed) }
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

        // Header: close + dots
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
            setOnClickListener { finishWithResult(CancelFlowResult.Dismissed) }
        }
        header.addView(closeButton)

        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER }
        }
        this.dotsContainer = dots
        header.addView(dots)

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
        this.contentContainer = content
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

        val primary = TextView(this).apply {
            text = "Continue"
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
            setOnClickListener { onPrimaryTap() }
        }
        this.primaryButton = primary
        buttonContainer.addView(primary)

        val secondary = TextView(this).apply {
            text = "Skip and cancel subscription"
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            gravity = Gravity.CENTER
            val topPad = (12 * density).toInt()
            setPadding(0, topPad, 0, (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { onSecondaryTap() }
        }
        this.secondaryButton = secondary
        buttonContainer.addView(secondary)

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

        // Render first question
        renderCurrentPage()
    }

    // -- Rendering --

    private fun renderCurrentPage() {
        contentContainer?.removeAllViews()
        updateDots()

        if (showingRetention) {
            renderRetentionPage()
        } else if (currentQuestionIndex < config.questions.size) {
            renderQuestion(config.questions[currentQuestionIndex])
        }
    }

    private fun updateDots() {
        val dots = dotsContainer ?: return
        dots.removeAllViews()
        val density = resources.displayMetrics.density
        for (i in 0 until totalSteps) {
            val dot = View(this).apply {
                val size = (8 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (i > 0) leftMargin = (6 * density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i <= currentStep) GREEN_COLOR else 0xFFDDDDDD.toInt())
                }
            }
            dots.addView(dot)
        }
    }

    private fun renderQuestion(question: CancelFlowQuestion) {
        val density = resources.displayMetrics.density
        val container = contentContainer ?: return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // Question text
        val title = TextView(this).apply {
            text = question.questionText
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        layout.addView(title)

        if (!question.isRequired) {
            val optional = TextView(this).apply {
                text = "Optional"
                textSize = 14f
                setTextColor(0xFF999999.toInt())
                setPadding(0, (4 * density).toInt(), 0, 0)
            }
            layout.addView(optional)
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (16 * density).toInt(),
            )
        }
        layout.addView(spacer)

        when (question.questionType) {
            CancelFlowQuestionType.SINGLE_SELECT -> {
                selectedOptionId = null
                val radioGroup = RadioGroup(this).apply {
                    orientation = RadioGroup.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(0xFFF5F5F5.toInt())
                        cornerRadius = 12 * density
                    }
                    val pad = (4 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                }

                for (option in question.options) {
                    val radio = RadioButton(this).apply {
                        id = option.id
                        text = option.label
                        textSize = 16f
                        setTextColor(Color.BLACK)
                        val vertPad = (12 * density).toInt()
                        val horizPad = (12 * density).toInt()
                        setPadding(horizPad, vertPad, horizPad, vertPad)
                    }
                    radioGroup.addView(radio)
                }

                radioGroup.setOnCheckedChangeListener { _, checkedId ->
                    selectedOptionId = checkedId
                    updatePrimaryEnabled(true)
                }
                layout.addView(radioGroup)
                updatePrimaryEnabled(!question.isRequired)
            }

            CancelFlowQuestionType.FREE_TEXT -> {
                freeTextInput = ""
                val editText = EditText(this).apply {
                    hint = "Type your response here..."
                    textSize = 16f
                    setTextColor(Color.BLACK)
                    minLines = 4
                    this.gravity = Gravity.TOP or Gravity.START
                    background = GradientDrawable().apply {
                        setColor(0xFFF5F5F5.toInt())
                        cornerRadius = 12 * density
                    }
                    val pad = (12 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                editText.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        freeTextInput = s?.toString() ?: ""
                        updatePrimaryEnabled(!question.isRequired || freeTextInput.isNotBlank())
                    }
                })
                layout.addView(editText)
                updatePrimaryEnabled(!question.isRequired)
            }
        }

        container.addView(layout)

        primaryButton?.text = "Continue"
        secondaryButton?.text = "Skip and cancel subscription"
        secondaryButton?.visibility = View.VISIBLE
    }

    /**
     * Renders the retention page with offer section and/or pause section.
     * The offer section is shown first (if enabled), followed by the pause section.
     * The primary button accepts the offer; the pause CTA is inline in the pause section.
     */
    private fun renderRetentionPage() {
        val density = resources.displayMetrics.density
        val container = contentContainer ?: return
        val offer = config.offer?.takeIf { it.enabled }
        val pause = config.pause?.takeIf { it.enabled && it.options.isNotEmpty() }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // -- Offer section --
        if (offer != null) {
            offerShown = true

            val topSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (24 * density).toInt(),
                )
            }
            layout.addView(topSpacer)

            val icon = TextView(this).apply {
                text = "\uD83C\uDF81"
                textSize = 44f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            layout.addView(icon)

            val spacer1 = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (16 * density).toInt(),
                )
            }
            layout.addView(spacer1)

            val titleView = TextView(this).apply {
                text = offer.title
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

            val bodyView = TextView(this).apply {
                text = offer.body
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

        // -- Pause section --
        if (pause != null) {
            pauseShown = true
            selectedPauseOptionId = null

            // Divider between offer and pause (if both present)
            if (offer != null) {
                val dividerSpacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (24 * density).toInt(),
                    )
                }
                layout.addView(dividerSpacer)

                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt(),
                    )
                    setBackgroundColor(0xFFE0E0E0.toInt())
                }
                layout.addView(divider)

                val afterDivider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (24 * density).toInt(),
                    )
                }
                layout.addView(afterDivider)
            } else {
                val topSpacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (24 * density).toInt(),
                    )
                }
                layout.addView(topSpacer)
            }

            val pauseTitle = TextView(this).apply {
                text = pause.title
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            layout.addView(pauseTitle)

            val pauseSpacer1 = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (8 * density).toInt(),
                )
            }
            layout.addView(pauseSpacer1)

            val pauseBody = TextView(this).apply {
                text = pause.body
                textSize = 15f
                setTextColor(0xFF666666.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            layout.addView(pauseBody)

            val pauseSpacer2 = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (12 * density).toInt(),
                )
            }
            layout.addView(pauseSpacer2)

            // Pause duration radio buttons
            val pauseRadioGroup = RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFFF5F5F5.toInt())
                    cornerRadius = 12 * density
                }
                val pad = (4 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            val sortedOptions = pause.options.sortedBy { it.order }
            for (option in sortedOptions) {
                val radio = RadioButton(this).apply {
                    id = option.id
                    text = option.label
                    textSize = 16f
                    setTextColor(Color.BLACK)
                    val vertPad = (12 * density).toInt()
                    val horizPad = (12 * density).toInt()
                    setPadding(horizPad, vertPad, horizPad, vertPad)
                }
                pauseRadioGroup.addView(radio)
            }

            // Inline pause CTA button (below radio options)
            val pauseCta = TextView(this).apply {
                text = pause.ctaText
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val pad = (12 * density).toInt()
                setPadding(0, pad, 0, pad)
                background = GradientDrawable().apply {
                    setColor(BLUE_COLOR)
                    cornerRadius = 10 * density
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (12 * density).toInt()
                }
                alpha = 0.4f
                isClickable = false
                setOnClickListener { onPauseTap() }
            }

            pauseRadioGroup.setOnCheckedChangeListener { _, checkedId ->
                selectedPauseOptionId = checkedId
                pauseCta.alpha = 1f
                pauseCta.isClickable = true
            }
            layout.addView(pauseRadioGroup)
            layout.addView(pauseCta)
        }

        container.addView(layout)

        // Configure bottom buttons for retention page
        if (offer != null) {
            primaryButton?.text = offer.ctaText
            updatePrimaryEnabled(true)
            secondaryButton?.text = "No thanks, cancel"
            secondaryButton?.visibility = View.VISIBLE
        } else {
            // Pause only (no offer) — primary button is "Cancel subscription"
            primaryButton?.text = "Cancel subscription"
            (primaryButton?.background as? GradientDrawable)?.setColor(0xFFD32F2F.toInt())
            updatePrimaryEnabled(true)
            secondaryButton?.visibility = View.GONE
        }
    }

    private fun updatePrimaryEnabled(enabled: Boolean) {
        primaryButton?.apply {
            alpha = if (enabled) 1f else 0.4f
            isClickable = enabled
        }
    }

    // -- Button Handlers --

    private fun onPrimaryTap() {
        if (showingRetention) {
            val offer = config.offer?.takeIf { it.enabled }
            if (offer != null) {
                // Primary button on retention page = accept the offer
                finishWithResult(CancelFlowResult.Retained)
            } else {
                // Pause-only retention page: primary = cancel subscription
                finishWithResult(CancelFlowResult.Cancelled)
            }
            return
        }

        // Record answer
        val question = config.questions.getOrNull(currentQuestionIndex) ?: return
        when (question.questionType) {
            CancelFlowQuestionType.SINGLE_SELECT -> {
                if (question.isRequired && selectedOptionId == null) return
                answers.add(AnswerData(question.id, selectedOptionId, null))
            }
            CancelFlowQuestionType.FREE_TEXT -> {
                val text = freeTextInput.trim()
                if (question.isRequired && text.isEmpty()) return
                answers.add(AnswerData(question.id, null, text.ifEmpty { null }))
            }
        }

        // Check trigger
        if (question.questionType == CancelFlowQuestionType.SINGLE_SELECT) {
            val optionId = selectedOptionId
            if (optionId != null) {
                val option = question.options.firstOrNull { it.id == optionId }
                if ((option?.triggersOffer == true || option?.triggersPause == true) && hasRetentionPage) {
                    earlyOfferTriggered = true
                    lastStepSeen = config.questions.size
                    showingRetention = true
                    renderCurrentPage()
                    return
                }
            }
        }

        // Advance
        val nextIndex = currentQuestionIndex + 1
        if (nextIndex < config.questions.size) {
            currentQuestionIndex = nextIndex
            lastStepSeen = maxOf(lastStepSeen, nextIndex)
            renderCurrentPage()
        } else {
            if (hasRetentionPage) {
                lastStepSeen = config.questions.size
                showingRetention = true
                renderCurrentPage()
            } else {
                finishWithResult(CancelFlowResult.Cancelled)
            }
        }
    }

    private fun onSecondaryTap() {
        finishWithResult(CancelFlowResult.Cancelled)
    }

    /**
     * Called when the user taps the pause CTA button on the retention page.
     * Calls the backend to pause the subscription, then finishes with [CancelFlowResult.Paused].
     */
    private fun onPauseTap() {
        val pauseOptionId = selectedPauseOptionId ?: return
        val pauseOption = config.pause?.options?.firstOrNull { it.id == pauseOptionId }

        // Disable the button to prevent double-taps
        updatePrimaryEnabled(false)

        scope.launch {
            try {
                val backend = ZeroSettle.effectiveBaseUrl?.let { baseUrl ->
                    ZeroSettle.currentConfig?.let { config ->
                        com.zerosettle.sdk.internal.Backend(baseUrl, config.publishableKey)
                    }
                }

                var resumesAt: String? = pauseOption?.resumeDate
                if (backend != null) {
                    val response = backend.pauseSubscription(productId, userId, pauseOptionId)
                    resumesAt = response.resumesAt ?: resumesAt
                }

                finishWithResult(CancelFlowResult.Paused(resumesAt = resumesAt))
            } catch (e: Exception) {
                ZSLogger.error("Failed to pause subscription: $e", ZSLogger.Category.IAP)
                // On failure, still let the user know — finish with paused using local date
                finishWithResult(CancelFlowResult.Paused(resumesAt = pauseOption?.resumeDate))
            }
        }
    }

    // -- Results --

    private fun finishWithResult(result: CancelFlowResult) {
        if (hasCompleted) return
        hasCompleted = true

        ZeroSettle.cancelFlowDeferred?.complete(result)

        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        if (!hasCompleted) {
            ZeroSettle.cancelFlowDeferred?.complete(CancelFlowResult.Dismissed)
        }
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Use onBackPressedDispatcher", ReplaceWith("onBackPressedDispatcher"))
    override fun onBackPressed() {
        if (!hasCompleted) {
            finishWithResult(CancelFlowResult.Dismissed)
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
