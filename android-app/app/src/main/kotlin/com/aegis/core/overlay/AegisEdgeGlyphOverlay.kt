package com.aegis.core.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sin

enum class HudGlowMode {
    IDLE,
    STEP_ACTIVE,
    VLM_GLOW,
    BLOCKED
}

data class HudSnapshot(
    val mode: HudGlowMode = HudGlowMode.IDLE,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val statusTitle: String = "",
    val actionDetail: String = "",
    val completedSteps: List<String> = emptyList(),
    val memoryLabel: String = "",
    val vlmActive: Boolean = false
)

/**
 * Shared state bus driving both the system-wide Edge Glyph / Bottom Steps HUD
 * and the in-app Compose live step dock with minimal memory overhead.
 */
object AegisLiveHudState {
    private val _state = MutableStateFlow(HudSnapshot())
    val state: StateFlow<HudSnapshot> = _state.asStateFlow()

    @Volatile
    private var captureHidden: Boolean = false
    private val captureListeners = mutableListOf<(Boolean) -> Unit>()

    fun update(
        mode: HudGlowMode,
        currentStep: Int,
        totalSteps: Int,
        statusTitle: String,
        actionDetail: String,
        completedStepTag: String? = null,
        memoryLabel: String = _state.value.memoryLabel
    ) {
        val prev = _state.value
        val updatedTags = if (!completedStepTag.isNullOrBlank()) {
            (prev.completedSteps + completedStepTag).takeLast(5)
        } else if (mode == HudGlowMode.IDLE || (currentStep <= 1 && prev.currentStep == 0)) {
            if (mode == HudGlowMode.IDLE) emptyList() else prev.completedSteps
        } else {
            prev.completedSteps
        }
        _state.value = HudSnapshot(
            mode = mode,
            currentStep = currentStep,
            totalSteps = totalSteps,
            statusTitle = statusTitle,
            actionDetail = actionDetail,
            completedSteps = updatedTags,
            memoryLabel = memoryLabel,
            vlmActive = mode == HudGlowMode.VLM_GLOW
        )
    }

    fun resetSteps(memoryLabel: String = _state.value.memoryLabel) {
        _state.value = HudSnapshot(memoryLabel = memoryLabel)
    }

    fun clearToIdle() {
        _state.value = HudSnapshot(mode = HudGlowMode.IDLE, memoryLabel = _state.value.memoryLabel)
    }

    fun setCaptureHidden(hidden: Boolean) {
        captureHidden = hidden
        synchronized(captureListeners) {
            captureListeners.forEach { it(hidden) }
        }
    }

    fun isCaptureHidden(): Boolean = captureHidden

    fun addCaptureListener(listener: (Boolean) -> Unit) {
        synchronized(captureListeners) {
            captureListeners.add(listener)
        }
    }

    fun removeCaptureListener(listener: (Boolean) -> Unit) {
        synchronized(captureListeners) {
            captureListeners.remove(listener)
        }
    }
}

private enum class StripEdge { TOP, BOTTOM, LEFT, RIGHT }

/**
 * Lightweight 10dp perimeter strip view housed inside a single container WindowManager window.
 */
private class EdgeGlyphStripView(
    context: Context,
    private val edge: StripEdge
) : View(context) {

    private var mode = HudGlowMode.IDLE
    private var pulsePhase = 0.8f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastW = 0
    private var lastH = 0
    private var lastMode: HudGlowMode? = null
    private var cachedShader: LinearGradient? = null

    fun updateState(newMode: HudGlowMode, phase: Float) {
        mode = newMode
        pulsePhase = phase
        visibility = if (mode == HudGlowMode.IDLE) GONE else VISIBLE
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (mode == HudGlowMode.IDLE || width <= 0 || height <= 0) return
        if (lastW != width || lastH != height || lastMode != mode || cachedShader == null) {
            val colors = when (mode) {
                HudGlowMode.VLM_GLOW -> intArrayOf(
                    Color.parseColor("#FF22D3EE"),
                    Color.parseColor("#FF6366F1"),
                    Color.parseColor("#FFA855F7"),
                    Color.parseColor("#FF10B981")
                )
                HudGlowMode.BLOCKED -> intArrayOf(
                    Color.parseColor("#FFEF4444"),
                    Color.parseColor("#FFF97316")
                )
                else -> intArrayOf(
                    Color.parseColor("#CC10B981"),
                    Color.parseColor("#CC06B6D4")
                )
            }
            val horizontal = (edge == StripEdge.TOP || edge == StripEdge.BOTTOM)
            cachedShader = LinearGradient(
                0f, 0f,
                if (horizontal) width.toFloat() else 0f,
                if (horizontal) 0f else height.toFloat(),
                colors,
                null,
                Shader.TileMode.CLAMP
            )
            lastW = width
            lastH = height
            lastMode = mode
        }

        val pulse = 0.5f + 0.5f * sin(pulsePhase.toDouble()).toFloat()
        val isVlm = mode == HudGlowMode.VLM_GLOW
        paint.shader = cachedShader
        val mainAlpha = when {
            isVlm -> (165 + (90 * pulse)).toInt().coerceIn(0, 255)
            mode == HudGlowMode.BLOCKED -> 235
            else -> 140
        }

        paint.alpha = (mainAlpha * 0.42f).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        paint.alpha = mainAlpha
        val corePx = (if (isVlm) 4f else 2.5f) * resources.displayMetrics.density
        when (edge) {
            StripEdge.TOP -> canvas.drawRect(0f, 0f, width.toFloat(), corePx.coerceAtMost(height.toFloat()), paint)
            StripEdge.BOTTOM -> canvas.drawRect(0f, (height - corePx).coerceAtLeast(0f), width.toFloat(), height.toFloat(), paint)
            StripEdge.LEFT -> canvas.drawRect(0f, 0f, corePx.coerceAtMost(width.toFloat()), height.toFloat(), paint)
            StripEdge.RIGHT -> canvas.drawRect((width - corePx).coerceAtLeast(0f), 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}

/**
 * Manages a SINGLE non-touchable WindowManager FrameLayout containing all 4 Edge Glyph strips
 * and the Bottom Live Steps HUD, attached once when the service connects so transitions never block.
 */
class AegisEdgeGlyphWindowManager(
    private val context: Context,
    private val windowType: Int
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootOverlay: FrameLayout? = null
    private val edgeStrips = mutableListOf<EdgeGlyphStripView>()
    private var hudContainer: LinearLayout? = null
    private var hudBg: GradientDrawable? = null
    private var badgeView: TextView? = null
    private var titleView: TextView? = null
    private var detailView: TextView? = null
    private var trailView: TextView? = null
    private var currentMode: HudGlowMode = HudGlowMode.IDLE

    private val pulseAnimator = ValueAnimator.ofFloat(0.2f, 1.0f).apply {
        duration = 380L
        repeatCount = 0
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            if (currentMode == HudGlowMode.IDLE) return@addUpdateListener
            val level = anim.animatedValue as Float
            for (s in edgeStrips) {
                s.updateState(currentMode, level)
            }
        }
    }

    private val captureListener: (Boolean) -> Unit = { hidden ->
        rootOverlay?.post {
            rootOverlay?.alpha = if (hidden) 0f else 1f
        }
    }

    fun attach() {
        AegisLiveHudState.addCaptureListener(captureListener)
        ensureSingleOverlayAttached()
    }

    fun render(snapshot: HudSnapshot) {
        val overlay = rootOverlay ?: return
        val wasMode = currentMode
        currentMode = snapshot.mode
        if (snapshot.mode == HudGlowMode.IDLE) {
            pulseAnimator.cancel()
            overlay.visibility = View.GONE
            for (s in edgeStrips) s.updateState(HudGlowMode.IDLE, 0f)
            hudContainer?.visibility = View.GONE
            return
        }

        overlay.visibility = View.VISIBLE
        if (snapshot.mode == HudGlowMode.VLM_GLOW && wasMode != HudGlowMode.VLM_GLOW) {
            for (s in edgeStrips) s.updateState(snapshot.mode, 0.2f)
            pulseAnimator.cancel()
            pulseAnimator.start()
        } else if (!pulseAnimator.isRunning) {
            for (s in edgeStrips) s.updateState(snapshot.mode, 0.9f)
        }

        val borderColor = when (snapshot.mode) {
            HudGlowMode.VLM_GLOW -> Color.parseColor("#FF22D3EE")
            HudGlowMode.BLOCKED -> Color.parseColor("#FFEF4444")
            else -> Color.parseColor("#FF22C55E")
        }
        hudBg?.setStroke(dpInt(if (snapshot.mode == HudGlowMode.VLM_GLOW) 2 else 1), borderColor)

        titleView?.setTextColor(borderColor)
        titleView?.text = snapshot.statusTitle.ifBlank {
            if (snapshot.vlmActive) "SmolVLM-500M · Vision Glow" else "Lumen Agent Active"
        }

        val stepText = if (snapshot.totalSteps > 0) {
            "Step ${snapshot.currentStep}/${snapshot.totalSteps}"
        } else if (snapshot.currentStep > 0) {
            "Step ${snapshot.currentStep}"
        } else {
            "Active"
        }
        badgeView?.text = if (snapshot.memoryLabel.isNotBlank()) {
            "$stepText · ${snapshot.memoryLabel}"
        } else {
            stepText
        }

        detailView?.text = snapshot.actionDetail.ifBlank { "Executing on-device step..." }

        if (snapshot.completedSteps.isNotEmpty()) {
            trailView?.visibility = View.VISIBLE
            trailView?.text = snapshot.completedSteps.joinToString(" › ")
        } else {
            trailView?.visibility = View.GONE
        }

        hudContainer?.visibility = View.VISIBLE
    }

    fun detach() {
        pulseAnimator.cancel()
        AegisLiveHudState.removeCaptureListener(captureListener)
        edgeStrips.clear()
        rootOverlay?.let { v -> runCatching { wm.removeViewImmediate(v) } }
        rootOverlay = null
        hudContainer = null
    }

    private fun ensureSingleOverlayAttached() {
        if (rootOverlay != null) return

        val container = FrameLayout(context).apply {
            visibility = View.GONE
        }

        val stripPx = dpInt(10)
        fun addStrip(edge: StripEdge, w: Int, h: Int, grav: Int) {
            val v = EdgeGlyphStripView(context, edge)
            container.addView(v, FrameLayout.LayoutParams(w, h, grav))
            edgeStrips.add(v)
        }
        addStrip(StripEdge.TOP, FrameLayout.LayoutParams.MATCH_PARENT, stripPx, Gravity.TOP or Gravity.START)
        addStrip(StripEdge.BOTTOM, FrameLayout.LayoutParams.MATCH_PARENT, stripPx, Gravity.BOTTOM or Gravity.START)
        addStrip(StripEdge.LEFT, stripPx, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START)
        addStrip(StripEdge.RIGHT, stripPx, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.END)

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpFloat(18f)
            setColor(Color.parseColor("#EB090C12"))
            setStroke(dpInt(1), Color.parseColor("#FF22C55E"))
        }
        hudBg = bg

        val hud = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dpInt(14), dpInt(10), dpInt(14), dpInt(10))
            elevation = dpFloat(8f)
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#FF22D3EE"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val bView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor("#FFA1A1AA"))
            maxLines = 1
        }
        topRow.addView(tView)
        topRow.addView(bView)

        val dView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#FFF4F4F5"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dpInt(3), 0, 0)
        }

        val trView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor("#FF94A3B8"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dpInt(3), 0, 0)
            visibility = View.GONE
        }

        hud.addView(topRow)
        hud.addView(dView)
        hud.addView(trView)

        val hudLp = FrameLayout.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dpInt(22)
        }
        container.addView(hud, hudLp)

        val wParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        runCatching {
            wm.addView(container, wParams)
            rootOverlay = container
            hudContainer = hud
            titleView = tView
            badgeView = bView
            detailView = dView
            trailView = trView
        }
    }

    private fun dpInt(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun dpFloat(dp: Float): Float =
        dp * context.resources.displayMetrics.density
}
