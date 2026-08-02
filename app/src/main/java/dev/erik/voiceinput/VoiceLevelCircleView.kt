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
import kotlin.math.max
import kotlin.math.sin

/**
 * Center mic affordance with modern level bars:
 * - **Recording**: soft breath + reactive waveform bars
 * - **Paused**: frozen amber bars (no live energy)
 * - **Transcribing**: spinning arc + gentle pulse
 * - **Idle**: dim disc
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
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3.5f)
            strokeCap = Paint.Cap.ROUND
        }
    private val barPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    private val wavePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }

    private var mode = Mode.IDLE
    private var displayLevel = 0f
    private var targetLevel = 0f
    private var breath = 0f
    private var spin = 0f
    private var barPhase = 0f
    private val barLevels = FloatArray(BAR_COUNT) { 0.15f }
    private var breathAnimator: ValueAnimator? = null
    private var spinAnimator: ValueAnimator? = null
    private var levelAnimator: ValueAnimator? = null

    private val baseRadius get() = minOf(width, height) / 2f - dp(8f)

    private val colorActive get() = ContextCompat.getColor(context, R.color.ime_voice_active)
    private val colorSoft get() = ContextCompat.getColor(context, R.color.ime_voice_active_soft)
    private val colorSilent get() = ContextCompat.getColor(context, R.color.ime_voice_silent)
    private val colorSilentFill get() = ContextCompat.getColor(context, R.color.ime_voice_silent_fill)
    private val colorRing get() = ContextCompat.getColor(context, R.color.ime_voice_ring)
    private val colorPaused get() = ContextCompat.getColor(context, R.color.ime_voice_paused)

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
                startBreath(periodMs = 1400)
                startLevelSmoothing()
            }
            Mode.PAUSED -> {
                stopSpin()
                stopLevelSmoothing()
                targetLevel = 0f
                displayLevel = 0.2f
                startBreath(periodMs = 2200)
            }
            Mode.TRANSCRIBING -> {
                stopLevelSmoothing()
                startBreath(periodMs = 1000)
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

    fun setVoiceLevel(normalized: Float, voiceDetected: Boolean) {
        targetLevel = (normalized * 1.4f).coerceIn(0f, 1f)
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
            Mode.PAUSED -> drawPaused(canvas, cx, cy)
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
        val breathScale = 1f + (0.035f + 0.025f * (1f - lvl)) * sin(breath * Math.PI.toFloat() * 2f)

        fillPaint.color = lerpColor(colorSilentFill, colorSoft, 0.2f + 0.8f * lvl)
        fillPaint.alpha = (155 + 90 * lvl).toInt().coerceIn(130, 250)
        val coreR = baseRadius * (0.42f + 0.18f * lvl) * breathScale
        canvas.drawCircle(cx, cy, coreR, fillPaint)

        ringPaint.color = lerpColor(colorSilent, colorActive, 0.15f + 0.85f * lvl)
        ringPaint.alpha = (180 + 70 * lvl).toInt().coerceIn(160, 255)
        ringPaint.strokeWidth = dp(2.8f + 1.8f * lvl)
        canvas.drawCircle(cx, cy, baseRadius * (0.62f + 0.1f * lvl) * breathScale, ringPaint)

        drawBars(canvas, cx, cy, colorActive, live = true)

        if (lvl > 0.08f) {
            val t = lvl
            wavePaint.color = colorActive
            wavePaint.alpha = (50 + 90 * t).toInt().coerceIn(0, 160)
            wavePaint.strokeWidth = dp(1.4f + t)
            canvas.drawCircle(
                cx,
                cy,
                baseRadius * (0.78f + 0.14f * t + 0.02f * sin(breath * 6.28f)),
                wavePaint,
            )
        }
    }

    private fun drawPaused(canvas: Canvas, cx: Float, cy: Float) {
        val breathScale = 1f + 0.02f * sin(breath * Math.PI.toFloat() * 2f)
        fillPaint.color = colorPaused
        fillPaint.alpha = 90
        canvas.drawCircle(cx, cy, baseRadius * 0.44f * breathScale, fillPaint)
        ringPaint.color = colorPaused
        ringPaint.alpha = 220
        ringPaint.strokeWidth = dp(3f)
        canvas.drawCircle(cx, cy, baseRadius * 0.66f * breathScale, ringPaint)
        // Two pause glyphs
        fillPaint.alpha = 230
        val w = dp(5f)
        val h = dp(16f)
        val gap = dp(5f)
        canvas.drawRoundRect(cx - gap - w, cy - h / 2, cx - gap, cy + h / 2, dp(2f), dp(2f), fillPaint)
        canvas.drawRoundRect(cx + gap, cy - h / 2, cx + gap + w, cy + h / 2, dp(2f), dp(2f), fillPaint)
        drawBars(canvas, cx, cy, colorPaused, live = false)
    }

    private fun drawTranscribing(canvas: Canvas, cx: Float, cy: Float) {
        fillPaint.color = colorSoft
        fillPaint.alpha = 190
        val breathScale = 1f + 0.06f * sin(breath * Math.PI.toFloat() * 2f)
        canvas.drawCircle(cx, cy, baseRadius * 0.48f * breathScale, fillPaint)

        ringPaint.color = colorActive
        ringPaint.alpha = 230
        ringPaint.strokeWidth = dp(3.5f)
        val r = baseRadius * (0.70f + 0.08f * breath)
        canvas.save()
        canvas.rotate(spin * 360f, cx, cy)
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 260f, false, ringPaint)
        canvas.restore()
    }

    private fun drawBars(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        color: Int,
        live: Boolean,
    ) {
        val barCount = BAR_COUNT
        val maxH = baseRadius * 0.55f
        val minH = baseRadius * 0.12f
        val totalW = baseRadius * 0.95f
        val gap = totalW / barCount
        val stroke = max(dp(2.5f), gap * 0.45f)
        barPaint.strokeWidth = stroke
        barPaint.color = color
        val startX = cx - totalW / 2f + gap / 2f
        for (i in 0 until barCount) {
            val target =
                if (live) {
                    val jitter =
                        0.35f +
                            0.65f *
                            (
                                0.55f * displayLevel +
                                    0.45f *
                                    absSin(barPhase * 2.2f + i * 0.7f + displayLevel * 1.4f)
                            )
                    jitter.coerceIn(0.12f, 1f)
                } else {
                    // Frozen gentle pattern when paused
                    (0.25f + 0.15f * absSin(i * 0.9f)).coerceIn(0.15f, 0.45f)
                }
            // Smooth each bar
            barLevels[i] += (target - barLevels[i]) * if (live) 0.35f else 0.12f
            val h = minH + maxH * barLevels[i]
            val x = startX + i * gap
            barPaint.alpha = (120 + 120 * barLevels[i]).toInt().coerceIn(100, 255)
            canvas.drawLine(x, cy - h / 2f, x, cy + h / 2f, barPaint)
        }
    }

    private fun absSin(x: Float): Float = kotlin.math.abs(sin(x))

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
                    barPhase += 0.08f
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
                duration = 1200
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

    private fun startLevelSmoothing() {
        stopLevelSmoothing()
        levelAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L * 60
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val alpha = if (targetLevel > displayLevel) 0.5f else 0.16f
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

    companion object {
        private const val BAR_COUNT = 7
    }
}
