package dev.erik.voiceinput

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelTest {
    @Test
    fun detectsSilenceAndVoice() {
        val silent = ByteArray(640) { 0 }
        val silentRms = AudioLevel.rmsPcm16Le(silent, 0, silent.size)
        assertFalse(AudioLevel.isVoice(silentRms))

        val loud = ByteArray(640)
        for (i in 0 until 320) {
            loud[i * 2] = 0xFF.toByte()
            loud[i * 2 + 1] = 0x7F.toByte()
        }
        val loudRms = AudioLevel.rmsPcm16Le(loud, 0, loud.size)
        assertTrue(AudioLevel.isVoice(loudRms))
    }
}