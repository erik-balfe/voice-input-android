package dev.erik.voiceinput

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.sin

/**
 * Single “alive” orb for the center control — one visual language:
 * - **Recording**: soft core that grows/brightens with voice (high sensitivity)
 * - **Paused**: amber frozen core + pause marks
 * - **Processing**: arc progress around the core (smooth 0..1)
 * - **Idle**: dim core
 *
 * No mixed bars + rings — one entity reacts to sound / progress.
 */
class VoiceLevelCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    enum class Mode {
        IDLE,
        RECORDING,
        PAUSED,
        TRANSCRIBING,
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    private val progressPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

    private var mode = Mode.IDLE
    private var displayLevel = 0f
    private var targetLevel = 0f
    private var breath = 0f
    /** Smooth 0..1 processing progress drawn as ring. */
    private var progress = 0f
    private var breathAnimator: ValueAnimator? = null
    private var levelAnimator: ValueAnimator? = null

    private val baseRadius get() = minOf(width, height) / 2f - dp(10f)

    private val colorActive get() = ContextCompat.getColor(context, R.color.ime_voice_active)
    private val colorSoft get() = ContextCompat.getColor(context, R.color.ime_voice_active_soft)
    private val colorSilent get() = ContextCompat.getColor(context, R.color.ime_voice_silent)
    private val colorSilentFill get() = ContextCompat.getColor(context, R.color.ime_voice_silent_fill)
    private val colorRing get() = ContextCompat.getColor(context, R.color.ime_voice_ring)
    private val colorPaused get() = ContextCompat.getColor(context, R.color.ime_voice_paused)
    private val colorProgress get() = ContextCompat.getColor(context, R.color.ime_progress)

    init {
        isClickable = false
        isFocusable = false
    }

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        when (newMode) {
            Mode.RECORDING -> {
                progress = 0f
                targetLevel = 0f
                displayLevel = 0f
                startBreath(periodMs = 1300)
                startLevelSmoothing()
            }
            Mode.PAUSED -> {
                stopLevelSmoothing()
                progress = 0f
                targetLevel = 0f
                displayLevel = 0.15f
                startBreath(periodMs = 2400)
            }
            Mode.TRANSCRIBING -> {
                stopLevelSmoothing()
                targetLevel = 0f
                displayLevel = 0.25f
                startBreath(periodMs = 1100)
            }
            Mode.IDLE -> {
                stopBreath()
                stopLevelSmoothing()
                targetLevel = 0f
                displayLevel = 0f
                progress = 0f
            }
        }
        invalidate()
    }

    fun setVoiceLevel(normalized: Float, voiceDetected: Boolean) {
        targetLevel = normalized.coerceIn(0f, 1f)
        if (mode == Mode.RECORDING && levelAnimator == null) {
            startLevelSmoothing()
        }
    }

    /**
     * Processing fraction 0..1 for the ring.
     * **Strictly monotonic** — never decreases (avoids the “went back 15%” bug).
     * Call [resetProgress] only when starting a new process.
     */
    fun setProgress(fraction: Float) {
        val t = fraction.coerceIn(0f, 1f)
        if (t > progress) {
            // Light ease-up only when moving forward (no second pass that can undershoot).
            progress += (t - progress) * 0.28f
            if (t - progress < 0.002f) progress = t
        }
        if (t >= 0.999f) progress = 1f
        invalidate()
    }

    fun resetProgress() {
        progress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        when (mode) {
            Mode.IDLE -> drawIdle(canvas, cx, cy)
            Mode.RECORDING -> drawRecording(canvas, cx, cy)
            Mode.PAUSED -> drawPaused(canvas, cx, cy)
            Mode.TRANSCRIBING -> drawProcessing(canvas, cx, cy)
        }
    }

    private fun drawIdle(canvas: Canvas, cx: Float, cy: Float) {
        fillPaint.color = colorSilentFill
        fillPaint.alpha = 120
        canvas.drawCircle(cx, cy, baseRadius * 0.48f, fillPaint)
        ringPaint.color = colorRing
        ringPaint.alpha = 160
        ringPaint.strokeWidth = dp(2.5f)
        canvas.drawCircle(cx, cy, baseRadius * 0.62f, ringPaint)
    }

    private fun drawRecording(canvas: Canvas, cx: Float, cy: Float) {
        val lvl = displayLevel
        val breathScale = 1f + 0.05f * sin(breath * Math.PI.toFloat() * 2f)

        // Outer glow — strong travel so “hearing you” is obvious.
        if (lvl > 0.03f) {
            glowPaint.color = colorActive
            glowPaint.alpha = (40 + 130 * lvl).toInt().coerceIn(0, 200)
            canvas.drawCircle(cx, cy, baseRadius * (0.70f + 0.32f * lvl) * breathScale, glowPaint)
            // Second wider halo at high energy
            if (lvl > 0.35f) {
                glowPaint.alpha = (20 + 70 * lvl).toInt()
                canvas.drawCircle(
                    cx,
                    cy,
                    baseRadius * (0.85f + 0.28f * lvl) * breathScale,
                    glowPaint,
                )
            }
        }

        // Core: quiet = small/dim, speech = large/bright (wide size travel).
        fillPaint.color = lerpColor(colorSilentFill, colorActive, 0.05f + 0.95f * lvl)
        fillPaint.alpha = (90 + 165 * lvl).toInt().coerceIn(90, 255)
        val coreR = baseRadius * (0.30f + 0.48f * lvl) * breathScale
        canvas.drawCircle(cx, cy, coreR, fillPaint)

        ringPaint.color = lerpColor(colorSilent, colorActive, 0.05f + 0.95f * lvl)
        ringPaint.alpha = (140 + 115 * lvl).toInt().coerceIn(130, 255)
        ringPaint.strokeWidth = dp(2f + 3f * lvl)
        canvas.drawCircle(cx, cy, baseRadius * (0.52f + 0.28f * lvl) * breathScale, ringPaint)
    }

    private fun drawPaused(canvas: Canvas, cx: Float, cy: Float) {
        val breathScale = 1f + 0.02f * sin(breath * Math.PI.toFloat() * 2f)
        fillPaint.color = colorPaused
        fillPaint.alpha = 100
        canvas.drawCircle(cx, cy, baseRadius * 0.46f * breathScale, fillPaint)
        ringPaint.color = colorPaused
        ringPaint.alpha = 230
        ringPaint.strokeWidth = dp(3f)
        canvas.drawCircle(cx, cy, baseRadius * 0.64f * breathScale, ringPaint)
        fillPaint.alpha = 240
        val w = dp(5f)
        val h = dp(18f)
        val gap = dp(5f)
        canvas.drawRoundRect(cx - gap - w, cy - h / 2, cx - gap, cy + h / 2, dp(2f), dp(2f), fillPaint)
        canvas.drawRoundRect(cx + gap, cy - h / 2, cx + gap + w, cy + h / 2, dp(2f), dp(2f), fillPaint)
    }

    private fun drawProcessing(canvas: Canvas, cx: Float, cy: Float) {
        val breathScale = 1f + 0.05f * sin(breath * Math.PI.toFloat() * 2f)
        fillPaint.color = colorSoft
        fillPaint.alpha = 200
        canvas.drawCircle(cx, cy, baseRadius * 0.44f * breathScale, fillPaint)

        // Track
        ringPaint.color = colorRing
        ringPaint.alpha = 100
        ringPaint.strokeWidth = dp(4.5f)
        val r = baseRadius * 0.72f
        canvas.drawCircle(cx, cy, r, ringPaint)

        // Progress arc — fixed start at top; never rotate the arc (rotation looked like reverse).
        progressPaint.color = colorProgress
        progressPaint.alpha = 255
        progressPaint.strokeWidth = dp(4.5f)
        val p = progress.coerceIn(0f, 1f)
        val sweep = 360f * p
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, sweep, false, progressPaint)

        // Tiny “alive” tip on the arc head while waiting (does not move the filled portion).
        if (p in 0.02f..0.98f) {
            val ang = Math.toRadians((-90f + sweep).toDouble())
            val tx = cx + r * kotlin.math.cos(ang).toFloat()
            val ty = cy + r * kotlin.math.sin(ang).toFloat()
            fillPaint.color = colorProgress
            fillPaint.alpha = 220
            canvas.drawCircle(tx, ty, dp(3.5f), fillPaint)
        }
    }

    private fun startBreath(periodMs: Long) {
        stopBreath()
        breathAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = periodMs
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    breath = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    private fun stopBreath() {
        breathAnimator?.cancel()
        breathAnimator = null
        breath = 0f
    }

    private fun startLevelSmoothing() {
        stopLevelSmoothing()
        levelAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L * 60
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val alpha = if (targetLevel > displayLevel) 0.72f else 0.18f
                    displayLevel += (targetLevel - displayLevel) * alpha
                    if (displayLevel < 0.01f && targetLevel < 0.01f) displayLevel = 0f
                    invalidate()
                }
                start()
            }
    }

    private fun stopLevelSmoothing() {
        levelAnimator?.cancel()
        levelAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopBreath()
        stopLevelSmoothing()
        super.onDetachedFromWindow()
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val a1 = c1 ushr 24 and 0xFF
        val r1 = c1 ushr 16 and 0xFF
        val g1 = c1 ushr 8 and 0xFF
        val b1 = c1 and 0xFF
        val a2 = c2 ushr 24 and 0xFF
        val r2 = c2 ushr 16 and 0xFF
        val g2 = c2 ushr 8 and 0xFF
        val b2 = c2 and 0xFF
        val a = (a1 + (a2 - a1) * tt).toInt()
        val r = (r1 + (r2 - r1) * tt).toInt()
        val g = (g1 + (g2 - g1) * tt).toInt()
        val b = (b1 + (b2 - b1) * tt).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
