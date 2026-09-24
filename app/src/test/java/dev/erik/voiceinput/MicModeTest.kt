package dev.erik.voiceinput

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicModeTest {
    @Test
    fun fromPrefDefaultsToAuto() {
        assertEquals(MicMode.AUTO, MicMode.fromPref(null))
        assertEquals(MicMode.AUTO, MicMode.fromPref("nope"))
        assertEquals(MicMode.VOIP, MicMode.fromPref("voip"))
        assertEquals(MicMode.VOICE_RECOGNITION, MicMode.fromPref("voice_recognition"))
    }

    @Test
    fun audioSourceMapping() {
        assertEquals(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MicMode.audioSource(MicMode.VOIP, unprocessedSupported = true),
        )
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MicMode.audioSource(MicMode.AUTO, unprocessedSupported = true),
        )
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MicMode.audioSource(MicMode.AUTO, unprocessedSupported = false),
        )
        assertEquals(
            MediaRecorder.AudioSource.UNPROCESSED,
            MicMode.audioSource(MicMode.UNPROCESSED, unprocessedSupported = true),
        )
        assertEquals(
            MediaRecorder.AudioSource.MIC,
            MicMode.audioSource(MicMode.MIC, unprocessedSupported = true),
        )
    }

    @Test
    fun sourcePriorityPutsPreferredFirstThenFallbacks() {
        val auto = MicMode.sourcePriority(MicMode.AUTO, unprocessedSupported = true)
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, auto.first())
        assertTrue(auto.contains(MediaRecorder.AudioSource.MIC))

        val voip = MicMode.sourcePriority(MicMode.VOIP, unprocessedSupported = false)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, voip.first())
        assertTrue(voip.contains(MediaRecorder.AudioSource.VOICE_RECOGNITION))
        assertTrue(voip.contains(MediaRecorder.AudioSource.MIC))

        val raw = MicMode.sourcePriority(MicMode.UNPROCESSED, unprocessedSupported = true)
        assertEquals(MediaRecorder.AudioSource.UNPROCESSED, raw.first())

        val rawFallback =
            MicMode.sourcePriority(MicMode.UNPROCESSED, unprocessedSupported = false)
        assertEquals(MediaRecorder.AudioSource.MIC, rawFallback.first())

        val mic = MicMode.sourcePriority(MicMode.MIC, unprocessedSupported = true)
        assertEquals(MediaRecorder.AudioSource.MIC, mic.first())
        assertEquals(mic.distinct(), mic)
    }

    @Test
    fun voipUsesCommunicationAudioMode() {
        assertTrue(MicMode.usesCommunicationAudioMode(MicMode.VOIP))
        assertFalse(MicMode.usesCommunicationAudioMode(MicMode.AUTO))
        assertFalse(MicMode.usesCommunicationAudioMode(MicMode.MIC))
    }

    @Test
    fun recorderUsesSavedMicMode() {
        val candidates =
            listOf(
                java.io.File("src/main/java/dev/erik/voiceinput/PcmRecorder.kt"),
                java.io.File("app/src/main/java/dev/erik/voiceinput/PcmRecorder.kt"),
            )
        val src = candidates.first { it.isFile }.readText()
        assertTrue(src.contains("Prefs.getMicMode"))
        assertTrue(src.contains("MicMode.sourcePriority"))
        assertTrue(src.contains("usesCommunicationAudioMode"))
    }
}
