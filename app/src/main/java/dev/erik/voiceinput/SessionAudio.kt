package dev.erik.voiceinput

/**
 * Resolve product M4A bytes from a finished [PcmClip] for History + STT.
 * Prefer progressive encode; post-stop AAC if needed; null if nothing usable.
 */
object SessionAudio {
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

    fun shouldKeep(clip: PcmClip): Boolean = clip.durationMs >= VoicePipeline.MIN_DURATION_MS

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
