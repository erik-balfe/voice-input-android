package dev.erik.voiceinput

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.sin

/**
 * Center mic affordance:
 * - **Recording**: soft continuous pulse = “listening”; fill + outer rings grow with voice level
 *   (smoothed — no binary blue/gray blink).
 * - **Transcribing**: calm spinning/breathing ring while waiting for the API.
 * - **Idle**: static dim disc.
 */
class VoiceLevelCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    enum class Mode {
        IDLE,
        RECORDING,
        TRANSCRIBING,
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3.5f)
            strokeCap = Paint.Cap.ROUND
        }
    private val wavePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }

    private var mode = Mode.IDLE
    /** Smoothed 0..1 loudness for drawing. */
    private var displayLevel = 0f
    /** Latest raw target from mic. */
    private var targetLevel = 0f
    private var breath = 0f
    private var spin = 0f
    private var breathAnimator: ValueAnimator? = null
    private var spinAnimator: ValueAnimator? = null
    private var levelAnimator: ValueAnimator? = null

    private val baseRadius get() = minOf(width, height) / 2f - dp(8f)

    private val colorActive get() = ContextCompat.getColor(context, R.color.ime_voice_active)
    private val colorSoft get() = ContextCompat.getColor(context, R.color.ime_voice_active_soft)
    private val colorSilent get() = ContextCompat.getColor(context, R.color.ime_voice_silent)
    private val colorSilentFill get() = ContextCompat.getColor(context, R.color.ime_voice_silent_fill)
    private val colorRing get() = ContextCompat.getColor(context, R.color.ime_voice_ring)

    init {
        isClickable = false
        isFocusable = false
    }

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        when (newMode) {
            Mode.RECORDING -> {
                stopSpin()
                targetLevel = 0f
                displayLevel = 0f
                startBreath(periodMs = 1600)
                startLevelSmoothing()
            }
            Mode.TRANSCRIBING -> {
                stopLevelSmoothing()
                startBreath(periodMs = 1100)
                startSpin()
            }
            Mode.IDLE -> {
                stopBreath()
                stopSpin()
                stopLevelSmoothing()
                targetLevel = 0f
                displayLevel = 0f
            }
        }
        invalidate()
    }

    /**
     * @param normalized loudness 0..1 (already roughly scaled)
     * @param voiceDetected unused for hard switch — level alone drives look
     */
    fun setVoiceLevel(normalized: Float, voiceDetected: Boolean) {
        // Ignore binary flag for color; map loudness continuously.
        // Boost a bit so normal speech is clearly visible, clamp for peaks.
        targetLevel = (normalized * 1.35f).coerceIn(0f, 1f)
        if (mode == Mode.RECORDING && levelAnimator == null) {
            startLevelSmoothing()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        when (mode) {
            Mode.IDLE -> drawIdle(canvas, cx, cy)
            Mode.RECORDING -> drawRecording(canvas, cx, cy)
            Mode.TRANSCRIBING -> drawTranscribing(canvas, cx, cy)
        }
    }

    private fun drawIdle(canvas: Canvas, cx: Float, cy: Float) {
        fillPaint.color = colorSilentFill
        fillPaint.alpha = 140
        ringPaint.color = colorRing
        ringPaint.alpha = 200
        canvas.drawCircle(cx, cy, baseRadius * 0.52f, fillPaint)
        canvas.drawCircle(cx, cy, baseRadius * 0.70f, ringPaint)
    }

    private fun drawRecording(canvas: Canvas, cx: Float, cy: Float) {
        val lvl = displayLevel
        // Soft breath always — shows “listening” even in silence.
        val breathAmp = 0.04f + 0.03f * (1f - lvl)
        val breathScale = 1f + breathAmp * sin(breath * Math.PI.toFloat() * 2f)

        // Blend silent → active colors by level (no hard jump).
        val silentFill = colorSilentFill
        val activeFill = colorSoft
        fillPaint.color = lerpColor(silentFill, activeFill, 0.25f + 0.75f * lvl)
        fillPaint.alpha = (150 + 90 * lvl).toInt().coerceIn(120, 245)

        val coreR = baseRadius * (0.48f + 0.22f * lvl) * breathScale
        canvas.drawCircle(cx, cy, coreR, fillPaint)

        ringPaint.color = lerpColor(colorSilent, colorActive, 0.2f + 0.8f * lvl)
        ringPaint.alpha = (180 + 70 * lvl).toInt().coerceIn(160, 255)
        ringPaint.strokeWidth = dp(3f + 1.5f * lvl)
        canvas.drawCircle(cx, cy, baseRadius * (0.68f + 0.12f * lvl) * breathScale, ringPaint)

        // Outer voice waves — only when there is energy, soft fade.
        if (lvl > 0.05f) {
            val waves = 2
            for (w in 1..waves) {
                val t = (lvl * (1.1f - w * 0.15f)).coerceIn(0f, 1f)
                val waveR =
                    baseRadius *
                        (0.78f + 0.18f * t + w * 0.08f + 0.03f * sin(breath * 6.28f + w))
                wavePaint.color = colorActive
                wavePaint.alpha = (40 + 100 * t * (1f - w * 0.25f)).toInt().coerceIn(0, 160)
                wavePaint.strokeWidth = dp(1.5f + t)
                canvas.drawCircle(cx, cy, waveR, wavePaint)
            }
        }
    }

    private fun drawTranscribing(canvas: Canvas, cx: Float, cy: Float) {
        fillPaint.color = colorSoft
        fillPaint.alpha = 190
        val breathScale = 1f + 0.06f * sin(breath * Math.PI.toFloat() * 2f)
        canvas.drawCircle(cx, cy, baseRadius * 0.50f * breathScale, fillPaint)

        ringPaint.color = colorActive
        ringPaint.alpha = 230
        ringPaint.strokeWidth = dp(3.5f)
        val r = baseRadius * (0.72f + 0.08f * breath)
        // Arc that slowly rotates — “working”
        canvas.save()
        canvas.rotate(spin * 360f, cx, cy)
        canvas.drawArc(
            cx - r,
            cy - r,
            cx + r,
            cy + r,
            -90f,
            280f,
            false,
            ringPaint,
        )
        canvas.restore()

        wavePaint.color = colorActive
        wavePaint.alpha = 80
        canvas.drawCircle(cx, cy, r + dp(6f), wavePaint)
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

    private fun startSpin() {
        stopSpin()
        spinAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    spin = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    private fun stopSpin() {
        spinAnimator?.cancel()
        spinAnimator = null
        spin = 0f
    }

    /** Smooth targetLevel → displayLevel ~60fps. */
    private fun startLevelSmoothing() {
        stopLevelSmoothing()
        levelAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L * 60 // long; we only use the frame callbacks
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    // Attack fast, release slower — natural VU-meter feel.
                    val alpha = if (targetLevel > displayLevel) 0.45f else 0.18f
                    displayLevel += (targetLevel - displayLevel) * alpha
                    if (displayLevel < 0.01f && targetLevel < 0.01f) {
                        displayLevel = 0f
                    }
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
        stopSpin()
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
