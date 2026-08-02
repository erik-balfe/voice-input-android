package dev.erik.voiceinput

/**
 * Pure IME presentation helpers (unit-testable without Android framework).
 */
object ImeUi {
    /** Format elapsed ms as m:ss (no leading zero on minutes). */
    fun formatTimer(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        val mm = sec / 60
        val ss = sec % 60
        return "%d:%02d".format(mm, ss)
    }

    /**
     * Active listen time excluding paused spans.
     * [nowMs], [listenStartedAtMs], [pausedAccumMs], [pauseStartedAtMs]
     * (0 if not currently paused).
     */
    fun activeListenMs(
        nowMs: Long,
        listenStartedAtMs: Long,
        pausedAccumMs: Long,
        pauseStartedAtMs: Long,
    ): Long {
        if (listenStartedAtMs <= 0L) return 0L
        val pausedExtra =
            if (pauseStartedAtMs > 0L) (nowMs - pauseStartedAtMs).coerceAtLeast(0L) else 0L
        return (nowMs - listenStartedAtMs - pausedAccumMs - pausedExtra).coerceAtLeast(0L)
    }
}
