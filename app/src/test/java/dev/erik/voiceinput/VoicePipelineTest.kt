package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoicePipelineTest {
    @Test
    fun rejectsRecordingShorterThan500ms() {
        val short = ByteArray(VoicePipeline.MIN_PCM_BYTES - 1)
        val ex = assertThrows(SttException::class.java) { VoicePipeline.validatePcm(short) }
        assertEquals("Recording too short — speak at least half a second", ex.userMessage)
    }

    @Test
    fun acceptsRecordingAt500ms() {
        VoicePipeline.validatePcm(ByteArray(VoicePipeline.MIN_PCM_BYTES))
    }
}