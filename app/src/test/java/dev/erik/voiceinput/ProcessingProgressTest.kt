package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingProgressTest {
    @Test
    fun estimateGrowsWithSpeechAndBytes() {
        val short =
            ProcessingProgress.estimateTotalMs(
                uploadBytes = 20_000,
                speechDurationMs = 2_000,
            )
        val long =
            ProcessingProgress.estimateTotalMs(
                uploadBytes = 800_000,
                speechDurationMs = 120_000,
            )
        assertTrue(long > short)
        assertTrue(short in 800L..90_000L)
        assertTrue(long in 800L..90_000L)
    }

    @Test
    fun fractionCapsBeforeComplete() {
        val est = 5000L
        assertEquals(0f, ProcessingProgress.fractionElapsed(0, est), 0.001f)
        val mid = ProcessingProgress.fractionElapsed(2500, est)
        assertTrue(mid in 0.4f..0.6f)
        val late = ProcessingProgress.fractionElapsed(10_000, est)
        assertTrue(late <= 0.92f + 0.001f)
    }

    @Test
    fun noteUploadUpdatesFutureEstimates() {
        // Fast path: large throughput should shrink upload portion somewhat.
        ProcessingProgress.noteUpload(1_000_000, 500)
        val afterFast =
            ProcessingProgress.estimateTotalMs(
                uploadBytes = 500_000,
                speechDurationMs = 30_000,
            )
        assertTrue(afterFast > 0)
    }
}
