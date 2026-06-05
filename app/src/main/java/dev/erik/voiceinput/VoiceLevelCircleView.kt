package dev.erik.voiceinput

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

class VoiceLevelCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    enum class Mode {
        IDLE,
        RECORDING,
        TRANSCRIBING,
    }

    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
        }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            alpha = 120
        }

    private var mode = Mode.IDLE
    private var level = 0f
    private var isVoice = false
    private var pulsePhase = 0f
    private var pulseAnimator: ValueAnimator? = null

    private val baseRadius get() = minOf(width, height) / 2f - dp(8f)

    init {
        isClickable = false
        isFocusable = false
    }

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        when (newMode) {
            Mode.TRANSCRIBING -> startPulse()
            else -> stopPulse()
        }
        invalidate()
    }

    fun setVoiceLevel(normalized: Float, voiceDetected: Boolean) {
        level = normalized
        isVoice = voiceDetected
        if (mode == Mode.RECORDING) invalidate()
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
        ringPaint.color = ContextCompat.getColor(context, R.color.ime_voice_ring)
        fillPaint.color = ContextCompat.getColor(context, R.color.ime_voice_silent)
        fillPaint.alpha = 60
        canvas.drawCircle(cx, cy, baseRadius * 0.55f, fillPaint)
        canvas.drawCircle(cx, cy, baseRadius * 0.7f, ringPaint)
    }

    private fun drawRecording(canvas: Canvas, cx: Float, cy: Float) {
        val activeColor =
            ContextCompat.getColor(
                context,
                if (isVoice) R.color.ime_voice_active else R.color.ime_voice_silent,
            )
        val softColor = ContextCompat.getColor(context, R.color.ime_voice_active_soft)

        val voiceScale = if (isVoice) 0.72f + level * 0.28f else 0.58f
        val outerScale = if (isVoice) 0.88f + level * 0.12f else 0.72f

        fillPaint.color = if (isVoice) softColor else activeColor
        fillPaint.alpha = if (isVoice) (140 + level * 80).toInt() else 90
        canvas.drawCircle(cx, cy, baseRadius * voiceScale, fillPaint)

        ringPaint.color = activeColor
        canvas.drawCircle(cx, cy, baseRadius * outerScale, ringPaint)

        if (isVoice) {
            pulsePaint.color = softColor
            val pulseR = baseRadius * (outerScale + 0.06f + level * 0.08f)
            canvas.drawCircle(cx, cy, pulseR, pulsePaint)
        }
    }

    private fun drawTranscribing(canvas: Canvas, cx: Float, cy: Float) {
        ringPaint.color = ContextCompat.getColor(context, R.color.ime_voice_active)
        fillPaint.color = ContextCompat.getColor(context, R.color.ime_voice_active_soft)
        fillPaint.alpha = 100
        canvas.drawCircle(cx, cy, baseRadius * 0.6f, fillPaint)
        val r = baseRadius * (0.75f + pulsePhase * 0.12f)
        canvas.drawCircle(cx, cy, r, ringPaint)
    }

    private fun startPulse() {
        stopPulse()
        pulseAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    pulsePhase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulsePhase = 0f
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}