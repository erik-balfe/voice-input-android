package dev.erik.voiceinput

import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingProgressTest {
    @Test
    fun idealNeverHitsHundredWhileWaiting() {
        val est = 2000L
        val atPred = ProcessingProgress.idealFraction(2000, est)
        assertTrue("at prediction ~PRIMARY_CAP: $atPred", atPred in 0.80f..0.90f)
        val late = ProcessingProgress.idealFraction(8000, est)
        assertTrue("long overshoot still under HARD_CAP: $late", late < 0.97f)
        assertTrue(late > atPred)
    }

    @Test
    fun estimateInRealisticBandFromLogs() {
        val longTake =
            ProcessingProgress.estimateTotalMs(
                uploadBytes = 1_396_847,
                speechDurationMs = 230_144,
            )
        // With 8% buffer, still well under old 11s model
        assertTrue("long: $longTake", longTake in 2500L..6500L)

        val short =
            ProcessingProgress.estimateTotalMs(
                uploadBytes = 46_213,
                speechDurationMs = 7_360,
            )
        assertTrue("short: $short", short in 1000L..2800L)
    }

    @Test
    fun smoothIsMonotonic() {
        var d = 0f
        d = ProcessingProgress.smoothToward(d, 400, 2000)
        val a = d
        d = ProcessingProgress.smoothToward(d, 1200, 2000)
        assertTrue(d >= a)
        d = ProcessingProgress.smoothToward(d, 10_000, 2000)
        assertTrue(d <= ProcessingProgress.HARD_CAP + 0.001f)
    }
}
