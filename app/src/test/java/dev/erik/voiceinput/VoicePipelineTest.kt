package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun uploadMayTrimButHistoryStillSendsOriginal() {
        val pipeline = source("VoicePipeline.kt")
        assertTrue(pipeline.contains("AudioPreprocessor.prepareForStt"))
        assertTrue(pipeline.contains("forUpload !== pcm16k"))
        assertTrue(pipeline.contains("isTrimLongSilence"))
        val session = source("SessionPersistence.kt")
        assertFalse(session.contains("prepareForStt"))
        val audio = source("SessionAudio.kt")
        assertFalse(audio.contains("prepareForStt"))
        val history = source("HistoryActivity.kt")
        assertTrue(history.contains("clipFromStored"))
        assertTrue(history.contains("readAudioBytes"))
        val settings = source("SettingsActivity.kt")
        val advanced = settings.substringAfter("if (showAdvanced)")
        assertTrue(advanced.contains("setTrimLongSilence"))
        assertFalse(settings.substringBefore("if (showAdvanced)").contains("setTrimLongSilence"))
        val ime =
            listOf(
                File("src/main/res/layout/voice_input_ime.xml"),
                File("app/src/main/res/layout/voice_input_ime.xml"),
            ).first { it.isFile }.readText()
        assertFalse(ime.contains("trim_silence"))
        assertTrue(source("SilenceHintTracker.kt").contains("heardVoiceThisTake"))
    }

    private fun source(name: String): String =
        listOf(
            File("src/main/java/dev/erik/voiceinput/$name"),
            File("app/src/main/java/dev/erik/voiceinput/$name"),
        ).first { it.isFile }.readText()
}
