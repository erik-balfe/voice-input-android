package dev.erik.voiceinput

/**
 * Resolve product M4A bytes from a finished [PcmClip] for History + STT.
 * Prefer progressive encode; post-stop AAC if needed; null if nothing usable.
 */
object SessionAudio {
    /**
     * Accidental open/close or lock without real dictation.
     * Align with [PendingSttQueue.MEANINGFUL_MIN_MS] so History stays clean.
     */
    const val KEEP_HISTORY_MIN_MS = PendingSttQueue.MEANINGFUL_MIN_MS

    fun m4aBytes(clip: PcmClip): ByteArray? {
        clip.encodedUpload?.bytes?.takeIf { it.isNotEmpty() }?.let { return it }
        if (clip.pcm.isEmpty()) return null
        return try {
            val pcm16k =
                if (clip.sampleRate == PcmResampler.TARGET_HZ) {
                    clip.pcm
                } else {
                    PcmResampler.to16kMonoLe(clip.pcm, clip.sampleRate)
                }
            AacEncoder.encodePcm16MonoToM4a(pcm16k, PcmResampler.TARGET_HZ).bytes
        } catch (e: Exception) {
            DiagLog.w("session", "encode for store failed", "err" to e.message)
            null
        }
    }

    /** Explicit ✓ finish: allow slightly shorter takes than passive save-on-hide. */
    fun shouldKeepForFinish(clip: PcmClip): Boolean =
        clip.durationMs >= VoicePipeline.MIN_DURATION_MS

    /** Hide / lock / cancel: only keep meaningful takes. */
    fun shouldKeep(clip: PcmClip): Boolean = clip.durationMs >= KEEP_HISTORY_MIN_MS

    /**
     * Local silence heuristic for cancel/hide — drops pure noise/silence without API call.
     * Conservative: only when clearly dead (avoids cutting quiet speech).
     */
    fun isLikelySilent(clip: PcmClip): Boolean {
        if (clip.pcm.isEmpty()) return false // unknown (M4A-only) — don't junk locally
        val stats = AudioDiagnostics.analyze(clip.pcm, clip.sampleRate)
        // Peak ~500 / mean ~60 is well below normal speech on VOICE_RECOGNITION path.
        return stats.peakAbs < 500 && stats.meanAbs < 60.0
    }

    fun shouldSaveOnHide(clip: PcmClip): Boolean =
        shouldKeep(clip) && !isLikelySilent(clip)

    /** Build a clip suitable for STT from stored M4A (no PCM). */
    fun clipFromStored(
        m4a: ByteArray,
        durationMs: Long,
        sampleRate: Int = 16_000,
        sourceName: String = "history",
    ): PcmClip =
        PcmClip(
            pcm = ByteArray(0),
            sampleRate = sampleRate,
            sourceName = sourceName,
            encodedUpload = GrokSttClient.AudioUpload(m4a, "recording.m4a", "audio/mp4"),
            encodeFlushMs = 0L,
            durationMsHint = durationMs,
        )
}
