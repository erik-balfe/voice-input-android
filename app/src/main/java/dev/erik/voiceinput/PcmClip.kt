package dev.erik.voiceinput

/**
 * Mono PCM16 little-endian clip with the **actual** sample rate from [android.media.AudioRecord].
 * [encodedUpload] is optional progressive AAC/M4A built during capture (near-zero stop latency).
 * [durationMsHint] is used when PCM is empty (e.g. retranscribe from History M4A only).
 */
data class PcmClip(
    val pcm: ByteArray,
    val sampleRate: Int,
    val sourceName: String = "?",
    val encodedUpload: GrokSttClient.AudioUpload? = null,
    /** Time to flush progressive encoder at stop (ms); 0 if none. */
    val encodeFlushMs: Long = 0L,
    /** When ≥ 0, overrides duration derived from PCM (History retranscribe). */
    val durationMsHint: Long = -1L,
) {
    val durationMs: Long
        get() =
            when {
                durationMsHint >= 0L -> durationMsHint
                sampleRate <= 0 || pcm.isEmpty() -> 0L
                else -> pcm.size * 1000L / (sampleRate * 2L)
            }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmClip) return false
        return sampleRate == other.sampleRate && pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int = 31 * sampleRate + pcm.contentHashCode()
}
