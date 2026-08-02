package dev.erik.voiceinput

import kotlin.math.exp
import kotlin.math.pow

/**
 * Progress for the processing ring — shows *tendency* of speed, not a hard ETA.
 *
 * Design (user feedback):
 * - Never slam to 100% while work is still running (looks like a bug).
 * - Reach ~[PRIMARY_CAP] around the predicted wall time, then crawl slowly.
 * - Slightly conservative estimate so we under-run more often than stick at 99%.
 *
 * Logs (progressive M4A): long takes ~2–4 s wall; short ~1–1.5 s.
 */
object ProcessingProgress {
    const val DEFAULT_EFFECTIVE_BPS = 520_000.0

    /** Progress at predicted completion time (not 100%). */
    const val PRIMARY_CAP = 0.88f

    /** Absolute max while still waiting for network/server. */
    const val HARD_CAP = 0.96f

    @Volatile
    private var effectiveBpsEma: Double = DEFAULT_EFFECTIVE_BPS

    fun noteUpload(bytes: Int, elapsedMs: Long) {
        if (bytes <= 0 || elapsedMs < 80L) return
        val netMs = (elapsedMs - 350L).coerceAtLeast(80L)
        val bps = bytes * 1000.0 / netMs
        if (bps < 40_000 || bps > 8_000_000) return
        effectiveBpsEma = effectiveBpsEma * 0.65 + bps * 0.35
    }

    fun estimateTotalMs(
        uploadBytes: Int,
        speechDurationMs: Long,
        authLikelyCached: Boolean = true,
    ): Long {
        val authMs = if (authLikelyCached) 120L else 380L
        val bookkeepingMs = 200L
        val bps = effectiveBpsEma.coerceIn(80_000.0, 2_500_000.0)
        val httpMs =
            (450.0 + uploadBytes / bps * 1000.0)
                .toLong()
                .coerceIn(400L, 45_000L)
        val speechMs = (speechDurationMs / 1000.0 * 8.0).toLong().coerceIn(0L, 2_000L)
        // Mild buffer so primary phase is a bit *longer* than median success (avoids early 100%).
        val raw = authMs + bookkeepingMs + httpMs + speechMs
        return (raw * 1.08).toLong().coerceIn(1_000L, 60_000L)
    }

    /**
     * Ideal progress for [elapsedMs] given [estimateTotalMs].
     *
     * - 0..estimate: ease-out toward [PRIMARY_CAP] (fast start, slows near prediction)
     * - past estimate: exponential crawl toward [HARD_CAP], never reaches 1.0
     */
    fun idealFraction(elapsedMs: Long, estimateTotalMs: Long): Float {
        if (estimateTotalMs <= 0L) return 0f
        val t = elapsedMs.toDouble() / estimateTotalMs.toDouble()
        return if (t <= 1.0) {
            // easeOutCubic: 1 - (1-t)^3 — quick early motion, decelerate into PRIMARY_CAP
            val eased = 1.0 - (1.0 - t).coerceIn(0.0, 1.0).pow(3.0)
            (PRIMARY_CAP * eased).toFloat()
        } else {
            // After prediction: very slow approach to HARD_CAP
            val over = t - 1.0
            val crawl = 1.0 - exp(-over * 0.85)
            (PRIMARY_CAP + (HARD_CAP - PRIMARY_CAP) * crawl).toFloat().coerceAtMost(HARD_CAP)
        }
    }

    /**
     * Smooth displayed value toward ideal (buttery ring, no 1% jerks).
     */
    fun smoothToward(
        display: Float,
        elapsedMs: Long,
        estimateTotalMs: Long,
        alpha: Float = 0.14f,
    ): Float {
        val target = idealFraction(elapsedMs, estimateTotalMs)
        val next = display + (target - display) * alpha
        return next.coerceIn(0f, HARD_CAP).coerceAtLeast(display)
    }

    /** Snap toward full only when work is truly done (short ease on last frames). */
    fun finish(display: Float): Float = 1f
}
