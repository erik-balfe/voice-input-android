package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAudioTest {
    @Test
    fun shouldKeepUsesPipelineMinimum() {
        val short =
            PcmClip(ByteArray(100), sampleRate = 16_000, durationMsHint = 100)
        val ok = PcmClip(ByteArray(16_000), sampleRate = 16_000) // 0.5s
        assertFalse(SessionAudio.shouldKeep(short))
        assertTrue(SessionAudio.shouldKeep(ok))
    }

    @Test
    fun m4aBytesPrefersProgressiveUpload() {
        val progressive =
            GrokSttClient.AudioUpload(ByteArray(50) { 1 }, "recording.m4a", "audio/mp4")
        val clip =
            PcmClip(
                pcm = ByteArray(32_000),
                sampleRate = 16_000,
                encodedUpload = progressive,
            )
        val bytes = SessionAudio.m4aBytes(clip)
        assertNotNull(bytes)
        assertEquals(50, bytes!!.size)
        assertEquals(1, bytes[0].toInt())
    }

    @Test
    fun m4aBytesNullWhenEmptyClip() {
        val clip = PcmClip(pcm = ByteArray(0), sampleRate = 16_000, durationMsHint = 0)
        assertNull(SessionAudio.m4aBytes(clip))
    }

    @Test
    fun clipFromStoredCarriesDurationAndMime() {
        val m4a = ByteArray(80) { 2 }
        val clip = SessionAudio.clipFromStored(m4a, durationMs = 3500, sampleRate = 16_000)
        assertEquals(3500L, clip.durationMs)
        assertEquals(0, clip.pcm.size)
        assertNotNull(clip.encodedUpload)
        assertEquals("audio/mp4", clip.encodedUpload!!.mimeType)
        VoicePipeline.validateClip(clip)
    }
}
