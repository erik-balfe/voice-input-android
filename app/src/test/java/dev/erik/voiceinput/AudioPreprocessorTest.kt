package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPreprocessorTest {
    @Test
    fun trimLeadingSilenceKeepsTrailingPause() {
        val silent = ByteArray(6400) // 200 ms silence
        val loud = ByteArray(6400)
        for (i in loud.indices step 2) {
            loud[i] = 0x00
            loud[i + 1] = 0x30.toByte()
        }
        val pcm = silent + loud + silent
        val trimmed = AudioPreprocessor.trimLeadingSilence(pcm)
        assertTrue(trimmed.size > loud.size)
        assertEquals(pcm.size - silent.size, trimmed.size)
    }

    @Test
    fun normalizePeakBoostsQuietAudio() {
        val quiet = ByteArray(200)
        for (i in quiet.indices step 2) {
            quiet[i] = 0x10
            quiet[i + 1] = 0x00
        }
        val normalized = AudioPreprocessor.normalizePeak(quiet)
        var peak = 0
        var i = 0
        while (i + 1 < normalized.size) {
            val lo = normalized[i].toInt() and 0xFF
            val hi = normalized[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val s = if (sample and 0x8000 != 0) sample or -0x10000 else sample
            peak = kotlin.math.max(peak, kotlin.math.abs(s))
            i += 2
        }
        assertTrue(peak > 0x10)
    }

    @Test
    fun addTailPaddingExtendsBuffer() {
        val pcm = ByteArray(3200)
        val padded = AudioPreprocessor.addTailPadding(pcm)
        assertEquals(pcm.size + 9600, padded.size) // 300 ms at 16 kHz mono PCM16
    }
}