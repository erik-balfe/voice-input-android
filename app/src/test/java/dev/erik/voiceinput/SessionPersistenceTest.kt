package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises [SessionPersistence] against a real [RecordingStore] (temp dir).
 * Avoids EncryptedSharedPreferences by injecting store + language/limits.
 */
class SessionPersistenceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun saveClipRoundTripThroughStore() {
        val store = RecordingStore(tmp.newFolder("recordings"))
        val progressive =
            GrokSttClient.AudioUpload(ByteArray(120) { 7 }, "recording.m4a", "audio/mp4")
        val clip =
            PcmClip(
                pcm = ByteArray(32_000),
                sampleRate = 16_000,
                encodedUpload = progressive,
                sourceName = "test",
            )
        val meta =
            SessionPersistence.saveClip(
                context = android.app.Application(), // unused when store injected
                clip = clip,
                status = SessionStatus.PENDING,
                sourcePackage = "unit.test",
                store = store,
                language = "en",
                maxItems = 50,
                maxBytes = RecordingStore.DEFAULT_MAX_BYTES,
            )
        assertNotNull(meta)
        val got = store.get(meta!!.id)
        assertNotNull(got)
        assertEquals(SessionStatus.PENDING, got!!.status)
        assertEquals(120L, got.audioBytes)
        assertEquals("unit.test", got.sourcePackage)
        assertTrue(store.audioFile(meta.id).isFile)
    }

    @Test
    fun saveClipRejectsEmptyAudio() {
        val store = RecordingStore(tmp.newFolder("empty"))
        val clip = PcmClip(pcm = ByteArray(0), sampleRate = 16_000, durationMsHint = 0)
        assertNull(
            SessionPersistence.saveClip(
                context = android.app.Application(),
                clip = clip,
                status = SessionStatus.PENDING,
                store = store,
                language = "en",
                maxItems = 50,
                maxBytes = RecordingStore.DEFAULT_MAX_BYTES,
            ),
        )
    }
}
