package dev.erik.voiceinput

import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingProgressTest {
    @Test
    fun estimateMatchesDeviceLogScaleOptimistic() {
        // From logs: 1.4MB / 230s speech → wall ~3.6s; old model was ~11s.
        val longTake =
            ProcessingProgress.estimateTotalMs(
                uploadBytes = 1_396_847,
                speechDurationMs = 230_144,
                authLikelyCached = true,
            )
        assertTrue("long take estimate should be near real ~3.5s not 11s: $longTake", longTake in 2000L..5500L)

        val mid =
            ProcessingProgress.estimateTotalMs(
                uploadBytes = 722_502,
                speechDurationMs = 118_912,
            )
        assertTrue("mid take: $mid", mid in 1500L..4000L)

        val short =
            ProcessingProgress.estimateTotalMs(
                uploadBytes = 46_213,
                speechDurationMs = 7_360,
            )
        assertTrue("short take: $short", short in 900L..2500L)

        // Long estimate should not dwarf wall time by 3× (user saw complete at 30%).
        assertTrue(longTake < 9000L)
    }

    @Test
    fun smoothProgressIsMonotonicAndCapped() {
        var d = 0f
        d = ProcessingProgress.smoothToward(d, 500, 3000)
        val mid = d
        d = ProcessingProgress.smoothToward(d, 1500, 3000)
        assertTrue(d >= mid)
        d = ProcessingProgress.smoothToward(d, 50_000, 3000)
        assertTrue(d <= 0.94f + 0.001f)
    }

    @Test
    fun noteUploadImprovesEma() {
        ProcessingProgress.noteUpload(1_000_000, 2000)
        val est =
            ProcessingProgress.estimateTotalMs(
                uploadBytes = 500_000,
                speechDurationMs = 60_000,
            )
        assertTrue(est > 0)
    }
}
