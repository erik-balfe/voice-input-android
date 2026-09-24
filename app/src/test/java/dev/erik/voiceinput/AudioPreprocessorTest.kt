package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    @Test
    fun longDigitalSilenceBetweenSpeechIsRemovedButPadsStay() {
        val speechA = constantPcm(ms = 800, amplitude = 8_000)
        val gap = ByteArray(bytesForMs(5_000))
        val speechB = constantPcm(ms = 800, amplitude = 12_000)
        val pcm = speechA + gap + speechB

        val out = AudioPreprocessor.prepareForStt(pcm)

        assertTrue(out.copyOfRange(0, speechA.size).contentEquals(speechA))
        assertTrue(out.copyOfRange(out.size - speechB.size, out.size).contentEquals(speechB))
        val padBytes = bytesForMs(AudioPreprocessor.PAD_MS)
        val mid = out.copyOfRange(speechA.size, out.size - speechB.size)
        assertEquals(padBytes * 2, mid.size)
        assertTrue(mid.all { it == 0.toByte() })
        assertTrue(out.size < pcm.size - bytesForMs(3_000))
    }

    @Test
    fun shortGapBetweenSpeechStays() {
        val speech = constantPcm(ms = 800, amplitude = 5_000)
        val gap = ByteArray(bytesForMs(200))
        val pcm = speech + gap + speech
        assertSame(pcm, AudioPreprocessor.prepareForStt(pcm))
    }

    @Test
    fun quietSpeechNearKeepThresholdIsNotDeleted() {
        val amp = (AudioPreprocessor.SPEECH_KEEP_MEAN_ABS - 12).toInt()
        val loud = constantPcm(ms = 600, amplitude = 8_000)
        val quiet = constantPcm(ms = 3_000, amplitude = amp)
        val silence = ByteArray(bytesForMs(5_000))
        val pcm = loud + quiet + silence + loud

        val out = AudioPreprocessor.prepareForStt(pcm)

        assertEquals(countAmplitude(quiet, amp), countAmplitude(out, amp))
        assertEquals(countAmplitude(loud, 8_000) * 2, countAmplitude(out, 8_000))
        assertTrue(out.size < pcm.size)
        assertTrue(out.size > quiet.size)
    }

    @Test
    fun allUncertainInputIsReturnedUnchanged() {
        val mid =
            ((AudioPreprocessor.CERTAIN_NON_SPEECH_MEAN_ABS + AudioPreprocessor.SPEECH_KEEP_MEAN_ABS) / 2)
                .toInt()
        val pcm = constantPcm(ms = 4_000, amplitude = mid)
        assertSame(pcm, AudioPreprocessor.prepareForStt(pcm))
    }

    private fun bytesForMs(ms: Int): Int = 16_000 * 2 * ms / 1000

    private fun constantPcm(ms: Int, amplitude: Int): ByteArray {
        val samples = 16_000 * ms / 1000
        val out = ByteArray(samples * 2)
        var i = 0
        while (i < samples) {
            out[i * 2] = (amplitude and 0xFF).toByte()
            out[i * 2 + 1] = ((amplitude shr 8) and 0xFF).toByte()
            i++
        }
        return out
    }

    private fun countAmplitude(pcm: ByteArray, amplitude: Int): Int {
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val s = if (sample and 0x8000 != 0) sample or -0x10000 else sample
            if (s == amplitude) n++
            i += 2
        }
        return n
    }
}