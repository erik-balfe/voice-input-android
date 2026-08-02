package dev.erik.voiceinput

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background STT for History rows that have audio but no transcript yet
 * (e.g. screen lock / hide mid-take → cancelled_saved).
 *
 * - Processes newest first, limited batch size
 * - Skips junk-length sessions
 * - Deletes rows with empty / no-speech results so History stays clean
 * - Single-flight: one runner at a time
 */
object PendingSttQueue {
    /** Do not keep or auto-STT accidental taps shorter than this. */
    const val MEANINGFUL_MIN_MS = 1_000L

    private const val MAX_BATCH = 5
    private val running = AtomicBoolean(false)

    /**
     * Fire-and-forget on a background thread. Safe to call from IME / activities often.
     * @return how many items were claimed for work (0 if busy or none)
     */
    fun kick(context: Context): Int {
        if (!XaiOauth.hasAnyAuth(context)) return 0
        if (!running.compareAndSet(false, true)) return 0
        val app = context.applicationContext
        Thread(
            {
                try {
                    processBatch(app)
                } catch (e: Exception) {
                    DiagLog.e("pending_stt", "batch failed", e)
                } finally {
                    running.set(false)
                }
            },
            "PendingSttQueue",
        ).start()
        return 1
    }

    /** Visible for tests — synchronous. */
    fun processBatch(context: Context, maxBatch: Int = MAX_BATCH): BatchResult {
        val store = RecordingStore.fromContext(context)
        // Drop ultra-short leftovers that slipped in earlier builds.
        var deletedJunk = 0
        for (meta in store.listNewestFirst()) {
            if (meta.hasText) continue
            if (meta.durationMs < MEANINGFUL_MIN_MS) {
                store.delete(meta.id)
                deletedJunk++
                DiagLog.i("pending_stt", "deleted junk short", "id" to meta.id, "ms" to meta.durationMs)
            }
        }

        val candidates =
            store.listNewestFirst()
                .filter { !it.hasText }
                .filter {
                    it.status == SessionStatus.PENDING ||
                        it.status == SessionStatus.CANCELLED_SAVED ||
                        it.status == SessionStatus.FAILED
                }
                .filter { it.durationMs >= MEANINGFUL_MIN_MS }
                .filter { it.canRetry }
                .take(maxBatch)

        var ok = 0
        var failed = 0
        var deletedEmpty = 0
        for (meta in candidates) {
            if (!XaiOauth.hasAnyAuth(context)) break
            val bytes = store.readAudioBytes(meta.id)
            if (bytes == null || bytes.isEmpty()) {
                store.delete(meta.id)
                deletedEmpty++
                continue
            }
            val clip =
                SessionAudio.clipFromStored(
                    m4a = bytes,
                    durationMs = meta.durationMs.coerceAtLeast(VoicePipeline.MIN_DURATION_MS),
                    sampleRate = meta.sampleRate,
                    sourceName = "pending",
                )
            try {
                DiagLog.i("pending_stt", "transcribe start", "id" to meta.id, "ms" to meta.durationMs)
                val text = VoicePipeline.transcribe(context, clip)
                if (isEmptyTranscript(text)) {
                    store.delete(meta.id)
                    deletedEmpty++
                    DiagLog.i("pending_stt", "deleted empty transcript", "id" to meta.id)
                } else {
                    store.markOk(meta.id, text)
                    ok++
                    DiagLog.i(
                        "pending_stt",
                        "transcribe ok",
                        "id" to meta.id,
                        "chars" to text.length,
                    )
                }
            } catch (e: Exception) {
                val msg =
                    when (e) {
                        is SttException -> e.userMessage
                        else -> SttException.wrap(e).userMessage
                    }
                // No-speech style failures → delete junk; network/auth → keep for later.
                if (isNoSpeechFailure(msg, e)) {
                    store.delete(meta.id)
                    deletedEmpty++
                    DiagLog.i("pending_stt", "deleted no-speech", "id" to meta.id, "err" to msg)
                } else {
                    store.markFailed(meta.id, msg)
                    failed++
                    DiagLog.w("pending_stt", "transcribe fail", "id" to meta.id, "err" to msg)
                }
            }
        }
        return BatchResult(
            transcribedOk = ok,
            failed = failed,
            deletedJunk = deletedJunk + deletedEmpty,
        )
    }

    fun isEmptyTranscript(text: String): Boolean = text.trim().isEmpty()

    fun isNoSpeechFailure(userMessage: String, cause: Throwable?): Boolean {
        val blob =
            buildString {
                append(userMessage)
                append(' ')
                append(cause?.message.orEmpty())
            }.lowercase()
        return blob.contains("no speech") ||
            blob.contains("empty transcript") ||
            blob.contains("no voice") ||
            blob.contains("could not detect")
    }

    data class BatchResult(
        val transcribedOk: Int,
        val failed: Int,
        val deletedJunk: Int,
    )
}
