package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises the real [RecordingStore] on a temp directory (no mocks of the store).
 */
class RecordingStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): RecordingStore = RecordingStore(tmp.newFolder("recordings"))

    private fun fakeM4a(n: Int = 64): ByteArray = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun saveListGetRoundTrip() {
        val s = store()
        val saved =
            s.save(
                m4aBytes = fakeM4a(100),
                durationMs = 1500,
                status = SessionStatus.PENDING,
                language = "en",
                sampleRate = 16_000,
                sourcePackage = "com.example.app",
                maxItems = 50,
                maxBytes = 500L * 1024 * 1024,
            )
        assertNotNull(saved)
        val id = saved!!.id
        assertTrue(s.audioFile(id).isFile)
        assertEquals(100L, s.audioFile(id).length())

        val listed = s.listNewestFirst()
        assertEquals(1, listed.size)
        assertEquals(id, listed[0].id)
        assertEquals(SessionStatus.PENDING, listed[0].status)
        assertEquals(1500L, listed[0].durationMs)
        assertEquals("com.example.app", listed[0].sourcePackage)

        val got = s.get(id)
        assertNotNull(got)
        assertEquals(saved, got)
        assertEquals(100L, got!!.audioBytes)
    }

    @Test
    fun markOkAndFailedUpdateStatus() {
        val s = store()
        val id =
            s.save(
                m4aBytes = fakeM4a(),
                durationMs = 2000,
                status = SessionStatus.PENDING,
                language = "ru",
            )!!.id

        val ok = s.markOk(id, "привет мир")
        assertNotNull(ok)
        assertEquals(SessionStatus.OK, ok!!.status)
        assertEquals("привет мир", ok.text)
        assertNull(ok.error)

        val fail = s.markFailed(id, "network down")
        assertNotNull(fail)
        assertEquals(SessionStatus.FAILED, fail!!.status)
        assertEquals("network down", fail.error)
        // text preserved when only markFailed
        assertEquals("привет мир", fail.text)
    }

    @Test
    fun pruneByMaxItemsDeletesOldest() {
        val s = store()
        val t0 = 1_700_000_000_000L
        val a =
            s.save(
                m4aBytes = fakeM4a(10),
                durationMs = 1000,
                status = SessionStatus.OK,
                language = "en",
                id = "a",
                createdAtMs = t0,
                maxItems = 10,
                maxBytes = 10_000_000,
            )!!.id
        val b =
            s.save(
                m4aBytes = fakeM4a(10),
                durationMs = 1000,
                status = SessionStatus.OK,
                language = "en",
                id = "b",
                createdAtMs = t0 + 1,
                maxItems = 10,
                maxBytes = 10_000_000,
            )!!.id
        val c =
            s.save(
                m4aBytes = fakeM4a(10),
                durationMs = 1000,
                status = SessionStatus.OK,
                language = "en",
                id = "c",
                createdAtMs = t0 + 2,
                maxItems = 2,
                maxBytes = 10_000_000,
            )!!.id

        val ids = s.listNewestFirst().map { it.id }.toSet()
        assertEquals(2, ids.size)
        assertTrue(ids.contains(c))
        assertTrue(ids.contains(b))
        assertFalse(ids.contains(a))
        assertFalse(s.audioFile(a).exists())
    }

    @Test
    fun pruneByMaxBytesDeletesOldest() {
        val s = store()
        val t0 = 1_700_000_000_000L
        s.save(
            m4aBytes = fakeM4a(100),
            durationMs = 1000,
            status = SessionStatus.OK,
            language = "en",
            id = "old",
            createdAtMs = t0,
            maxItems = 50,
            maxBytes = 10_000_000,
        )
        s.save(
            m4aBytes = fakeM4a(100),
            durationMs = 1000,
            status = SessionStatus.OK,
            language = "en",
            id = "new",
            createdAtMs = t0 + 1,
            maxItems = 50,
            maxBytes = 150, // only one 100-byte file fits
        )
        val ids = s.listNewestFirst().map { it.id }
        assertEquals(listOf("new"), ids)
        assertTrue(s.totalAudioBytes() <= 150)
        assertFalse(s.audioFile("old").exists())
    }

    @Test
    fun pruneProtectsNewestWhenOverBudget() {
        val s = store()
        val meta =
            s.save(
                m4aBytes = fakeM4a(200),
                durationMs = 3000,
                status = SessionStatus.PENDING,
                language = "en",
                id = "only",
                maxItems = 50,
                maxBytes = 50, // smaller than the one file
            )
        assertNotNull(meta)
        // Still listed — protectId keeps the just-saved take
        assertEquals(1, s.listNewestFirst().size)
        assertEquals("only", s.listNewestFirst()[0].id)
    }

    @Test
    fun emptyM4aNotSaved() {
        val s = store()
        assertNull(
            s.save(
                m4aBytes = ByteArray(0),
                durationMs = 5000,
                status = SessionStatus.PENDING,
                language = "en",
            ),
        )
        assertTrue(s.listNewestFirst().isEmpty())
    }

    @Test
    fun deleteRemovesAudioAndMeta() {
        val s = store()
        val id =
            s.save(
                m4aBytes = fakeM4a(),
                durationMs = 900,
                status = SessionStatus.CANCELLED_SAVED,
                language = "en",
            )!!.id
        assertTrue(s.delete(id))
        assertNull(s.get(id))
        assertFalse(s.audioFile(id).exists())
    }

    @Test
    fun sessionAudioShouldKeepUsesPipelineMin() {
        val short =
            PcmClip(
                pcm = ByteArray(100),
                sampleRate = 16_000,
                durationMsHint = 100,
            )
        val ok =
            PcmClip(
                pcm = ByteArray(16_000),
                sampleRate = 16_000,
            )
        assertFalse(SessionAudio.shouldKeep(short))
        assertTrue(SessionAudio.shouldKeep(ok))
        assertTrue(ok.durationMs >= VoicePipeline.MIN_DURATION_MS)
    }

    @Test
    fun clipFromStoredPreservesDurationForValidate() {
        val clip = SessionAudio.clipFromStored(fakeM4a(80), durationMs = 2500)
        assertEquals(2500L, clip.durationMs)
        VoicePipeline.validateClip(clip)
        assertNotNull(clip.encodedUpload)
        assertEquals(80, clip.encodedUpload!!.bytes.size)
    }
}
