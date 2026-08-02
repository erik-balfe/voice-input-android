package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeUiTest {
    @Test
    fun formatTimerBasic() {
        assertEquals("0:00", ImeUi.formatTimer(0))
        assertEquals("0:05", ImeUi.formatTimer(5_000))
        assertEquals("1:01", ImeUi.formatTimer(61_000))
        assertEquals("10:00", ImeUi.formatTimer(600_000))
    }

    @Test
    fun activeListenExcludesPausedTime() {
        val start = 1_000_000L
        // 10s wall, 3s paused total, currently paused 2s more → 5s active
        val active =
            ImeUi.activeListenMs(
                nowMs = start + 10_000,
                listenStartedAtMs = start,
                pausedAccumMs = 3_000,
                pauseStartedAtMs = start + 8_000,
            )
        assertEquals(5_000L, active)
    }

    @Test
    fun activeListenZeroWhenNotStarted() {
        assertEquals(
            0L,
            ImeUi.activeListenMs(
                nowMs = 100,
                listenStartedAtMs = 0,
                pausedAccumMs = 0,
                pauseStartedAtMs = 0,
            ),
        )
    }
}
