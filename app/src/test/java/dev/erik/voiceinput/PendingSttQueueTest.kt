package dev.erik.voiceinput

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSttQueueTest {
    @Test
    fun emptyTranscriptDetection() {
        assertTrue(PendingSttQueue.isEmptyTranscript(""))
        assertTrue(PendingSttQueue.isEmptyTranscript("   "))
        assertFalse(PendingSttQueue.isEmptyTranscript("hello"))
    }

    @Test
    fun noSpeechFailureDetection() {
        assertTrue(PendingSttQueue.isNoSpeechFailure("No speech detected — try again", null))
        assertTrue(PendingSttQueue.isNoSpeechFailure("fail", Exception("empty transcript from STT")))
        assertFalse(PendingSttQueue.isNoSpeechFailure("No network — check connection", null))
        assertFalse(PendingSttQueue.isNoSpeechFailure("Auth failed — sign in", null))
    }

    @Test
    fun shouldSaveOnHideRespectsOneSecondAndSilence() {
        val short =
            PcmClip(ByteArray(8_000), sampleRate = 16_000) // 0.25s
        assertFalse(SessionAudio.shouldSaveOnHide(short))

        // ~1.5s of near-silence
        val silentLong = PcmClip(ByteArray(48_000) { 0 }, sampleRate = 16_000)
        assertTrue(silentLong.durationMs >= SessionAudio.KEEP_HISTORY_MIN_MS)
        assertTrue(SessionAudio.isLikelySilent(silentLong))
        assertFalse(SessionAudio.shouldSaveOnHide(silentLong))

        // Speech-like PCM: non-zero samples
        val speech = ByteArray(48_000)
        for (i in 0 until 24_000) {
            speech[i * 2] = 0
            speech[i * 2 + 1] = 0x10 // ~4096 peak region
        }
        val speechClip = PcmClip(speech, sampleRate = 16_000)
        assertTrue(SessionAudio.shouldSaveOnHide(speechClip))
    }
}
