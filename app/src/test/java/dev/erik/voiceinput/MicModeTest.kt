package dev.erik.voiceinput

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
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
}
