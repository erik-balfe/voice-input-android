package dev.erik.voiceinput

/**
 * Optimistic wall-time estimate for post-stop STT (progressive encode already done).
 *
 * Calibrated from device logs (2026-08-03, progressive M4A + OAuth cache hit):
 * | speech | m4a   | wallMs | old estimate |
 * | 230s   | 1.4MB | 3595   | 11311 (too slow) |
 * | 119s   | 722KB | 2331   | 5949 |
 * | 55s    | 340KB | 2321   | 3592 |
 * | 11s    | 66KB  | 1544   | 1348 |
 * | 7s     | 46KB  | 1146   | 1257 |
 *
 * Model: fixed overhead + bytes/(optimistic B/s) + small speech term.
 * Prefer finishing near 90–100% for typical good networks, not at 30%.
 */
object ProcessingProgress {
    /** Default effective B/s for (upload + server) on a normal mobile network. */
    const val DEFAULT_EFFECTIVE_BPS = 620_000.0

    @Volatile
    private var effectiveBpsEma: Double = DEFAULT_EFFECTIVE_BPS

    /**
     * Observe a completed HTTP round-trip (upload+server).
     * [elapsedMs] is full STT HTTP time, not pure upload.
     */
    fun noteUpload(bytes: Int, elapsedMs: Long) {
        if (bytes <= 0 || elapsedMs < 80L) return
        // Strip ~350ms fixed server/TLS overhead so EMA tracks throughput, not RTT.
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
        val authMs = if (authLikelyCached) 100L else 350L
        // Persist + pipeline bookkeeping (from logs ~150–250ms)
        val bookkeepingMs = 180L
        val bps = effectiveBpsEma.coerceIn(80_000.0, 3_000_000.0)
        // HTTP ≈ fixed + payload/bps (matches log curve better than speech-heavy model)
        val httpMs =
            (420.0 + uploadBytes / bps * 1000.0)
                .toLong()
                .coerceIn(350L, 45_000L)
        // Tiny speech term — server work grows slowly once progressive M4A is small.
        val speechMs = (speechDurationMs / 1000.0 * 6.0).toLong().coerceIn(0L, 2_500L)
        // Slight optimism factor (0.92) so bar reaches high 90s on typical runs.
        val raw = authMs + bookkeepingMs + httpMs + speechMs
        return (raw * 0.92).toLong().coerceIn(900L, 60_000L)
    }

    /**
     * Smooth ease toward target elapsed fraction.
     * [display] is previous displayed 0..1; returns new display.
     */
    fun smoothToward(
        display: Float,
        elapsedMs: Long,
        estimateTotalMs: Long,
        cap: Float = 0.94f,
        alpha: Float = 0.18f,
    ): Float {
        if (estimateTotalMs <= 0L) return display
        val target = (elapsedMs.toFloat() / estimateTotalMs).coerceIn(0f, cap)
        // Always move forward; ease in so UI doesn't jump in 1% ticks.
        val next = display + (target - display) * alpha
        return next.coerceIn(0f, cap).coerceAtLeast(display)
    }

    fun complete(display: Float): Float = 1f.coerceAtLeast(display)
}
