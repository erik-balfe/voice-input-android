package dev.erik.voiceinput

import android.content.Context

/**
 * Single entry for writing a finished capture into History.
 * Used by IME and system [GrokRecognitionService].
 */
object SessionPersistence {
    fun saveClip(
        context: Context,
        clip: PcmClip,
        status: SessionStatus,
        sourcePackage: String? = null,
        store: RecordingStore = RecordingStore.fromContext(context),
        language: String = Prefs.getLanguage(context),
        maxItems: Int = Prefs.getHistoryMaxItems(context),
        maxBytes: Long = Prefs.getHistoryMaxBytes(context),
    ): RecordingMeta? {
        val bytes = SessionAudio.m4aBytes(clip) ?: return null
        return try {
            store.save(
                m4aBytes = bytes,
                durationMs = clip.durationMs,
                status = status,
                language = language,
                sampleRate = clip.sampleRate,
                sourcePackage = sourcePackage,
                maxItems = maxItems,
                maxBytes = maxBytes,
            )
        } catch (e: Exception) {
            DiagLog.e("session", "persist failed", e)
            null
        }
    }
}
