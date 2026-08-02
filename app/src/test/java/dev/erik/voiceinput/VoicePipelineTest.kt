package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoicePipelineTest {
    @Test
    fun rejectsRecordingShorterThan500ms() {
        val short = PcmClip(ByteArray(1000), sampleRate = 16_000)
        val ex = assertThrows(SttException::class.java) { VoicePipeline.validateClip(short) }
        assertEquals("Recording too short — speak at least half a second", ex.userMessage)
    }

    @Test
    fun acceptsRecordingAt500ms() {
        // 0.5 s @ 16 kHz mono PCM16 = 16000 bytes
        VoicePipeline.validateClip(PcmClip(ByteArray(16_000), sampleRate = 16_000))
    }

    @Test
    fun acceptsHalfSecondAt48k() {
        // 0.5 s @ 48 kHz = 48000 samples * 2 = 96000 bytes
        VoicePipeline.validateClip(PcmClip(ByteArray(96_000), sampleRate = 48_000))
    }
}
