package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmResamplerTest {
    @Test
    fun identityAt16k() {
        val pcm = ByteArray(3200) { i -> (i % 200).toByte() }
        val out = PcmResampler.to16kMonoLe(pcm, 16_000)
        assertTrue(out.contentEquals(pcm))
    }

    @Test
    fun downsample48kTo16kLength() {
        // 0.3 s @ 48 kHz mono = 14400 samples = 28800 bytes
        val pcm = ByteArray(28_800)
        for (i in pcm.indices step 2) {
            pcm[i] = 0
            pcm[i + 1] = 0x10
        }
        val out = PcmResampler.to16kMonoLe(pcm, 48_000)
        // ~0.3 s @ 16 kHz = 4800 samples = 9600 bytes
        assertEquals(9600, out.size)
    }
}
