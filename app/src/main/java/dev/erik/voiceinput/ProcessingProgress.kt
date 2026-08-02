package dev.erik.voiceinput

/**
 * Rough wall-clock estimate for STT processing so the IME can show a real-ish progress bar.
 * Not exact — calibrated from typical progressive-M4A + OAuth runs on device.
 *
 * Components:
 * - auth overhead (cached hit is small; miss larger — use midpoint)
 * - upload time from bytes / assumed throughput
 * - server STT time ~ base + f(speech duration, upload size)
 */
object ProcessingProgress {
    /** Default upload throughput when we have no samples yet (bytes/sec). */
    const val DEFAULT_UPLOAD_BPS = 350_000.0

    /** EMA of observed upload B/s (process-local). */
    @Volatile
    private var uploadBpsEma: Double = DEFAULT_UPLOAD_BPS

    fun noteUpload(bytes: Int, elapsedMs: Long) {
        if (bytes <= 0 || elapsedMs <= 0) return
        val bps = bytes * 1000.0 / elapsedMs
        // Clamp outliers (VPN stalls, tiny files).
        if (bps < 20_000 || bps > 5_000_000) return
        val prev = uploadBpsEma
        uploadBpsEma = prev * 0.7 + bps * 0.3
    }

    /**
     * Estimated total processing time in ms after mic stop (auth + upload + server).
     */
    fun estimateTotalMs(
        uploadBytes: Int,
        speechDurationMs: Long,
        authLikelyCached: Boolean = true,
    ): Long {
        val authMs = if (authLikelyCached) 150L else 400L
        val bps = uploadBpsEma.coerceAtLeast(50_000.0)
        val uploadMs = ((uploadBytes / bps) * 1000.0).toLong().coerceIn(200L, 60_000L)
        // Server: base + ~25ms per second of speech + small size term.
        val speechSec = speechDurationMs / 1000.0
        val serverMs =
            (700.0 + speechSec * 28.0 + uploadBytes / 50_000.0)
                .toLong()
                .coerceIn(500L, 45_000L)
        return (authMs + uploadMs + serverMs).coerceIn(800L, 90_000L)
    }

    /**
     * Map elapsed processing time to 0..1 progress.
     * Caps at [cap] until completion so we never stick at 100% waiting.
     */
    fun fractionElapsed(elapsedMs: Long, estimateTotalMs: Long, cap: Float = 0.92f): Float {
        if (estimateTotalMs <= 0L) return 0f
        val raw = elapsedMs.toFloat() / estimateTotalMs.toFloat()
        return raw.coerceIn(0f, cap)
    }
}
