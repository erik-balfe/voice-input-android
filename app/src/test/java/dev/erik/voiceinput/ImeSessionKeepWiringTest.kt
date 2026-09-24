package dev.erik.voiceinput

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural check: IME session-end paths must persist keep-worthy audio
 * (cannot drive full IME in unit tests without full system services).
 */
class ImeSessionKeepWiringTest {
    private fun imeSource(): String {
        val candidates =
            listOf(
                File("src/main/java/dev/erik/voiceinput/GrokVoiceInputMethodService.kt"),
                File("app/src/main/java/dev/erik/voiceinput/GrokVoiceInputMethodService.kt"),
            )
        val f = candidates.first { it.isFile }
        return f.readText()
    }

    @Test
    fun cancelAndFinishInputViewPersistInsteadOfOnlyCancel() {
        val src = imeSource()
        assertTrue(
            "cancel path should call saveOnlyIfKeepWorthy",
            src.contains("saveOnlyIfKeepWorthy"),
        )
        assertTrue(
            "finish input / hide should not only discard",
            src.contains("onFinishInputView") && src.contains("saveOnlyIfKeepWorthy"),
        )
        assertTrue(
            "cancelled saves use CANCELLED_SAVED status",
            src.contains("SessionStatus.CANCELLED_SAVED"),
        )
        assertTrue(
            "process path persists PENDING before STT",
            src.contains("SessionStatus.PENDING"),
        )
        assertTrue("STT success updates store", src.contains("markOk"))
        assertTrue("STT failure updates store", src.contains("markFailed"))
        // Must not be the only end path for hide:
        assertTrue(
            src.contains("recorder.stop()") ||
                src.contains("saveOnlyIfKeepWorthy"),
        )
        // Hide/back reuses the input view — must restart listen on show again.
        assertTrue(
            "onStartInputView must ensure a fresh listen session",
            src.contains("ensureFreshListenSession"),
        )
        assertTrue(
            "window hide should also keep audio",
            src.contains("onWindowHidden"),
        )
        assertTrue(
            "dictation mark button inserts even when auto-append is off",
            src.contains("insertDictationMark"),
        )
        assertTrue(
            "successful insert may auto-append dictation mark",
            src.contains("isDictationMarkEnabled"),
        )
        assertTrue(
            "local silence hints while listening",
            src.contains("SilenceHintTracker") && src.contains("ime_hint_no_voice"),
        )
    }

    @Test
    fun historyActivityExistsAndRetranscribesViaStore() {
        val hist =
            listOf(
                File("src/main/java/dev/erik/voiceinput/HistoryActivity.kt"),
                File("app/src/main/java/dev/erik/voiceinput/HistoryActivity.kt"),
            ).first { it.isFile }.readText()
        assertTrue(hist.contains("fun retranscribe"))
        assertTrue(hist.contains("RecordingStore"))
        assertTrue(hist.contains("markOk"))
        assertTrue(hist.contains("SessionAudio.clipFromStored"))
    }
}
