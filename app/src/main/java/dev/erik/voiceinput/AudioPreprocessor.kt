package dev.erik.voiceinput

/**
 * Legacy helpers kept for unit tests only.
 * Production path ([VoicePipeline]) does **no** trim/normalize/pad — raw PCM only.
 */
object AudioPreprocessor {
    private const val FRAME_SAMPLES = 320
    private const val TAIL_PADDING_MS = 300
    private const val TARGET_PEAK = 28_000
    private const val MIN_PEAK_TO_BOOST = 12_000

    /** Identity — production must not process audio. */
    fun prepareForStt(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray = pcm

    fun trimLeadingSilence(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
        if (pcm.size < 4) return pcm
        val sampleCount = pcm.size / 2
        val totalFrames = (sampleCount + FRAME_SAMPLES - 1) / FRAME_SAMPLES
        var startFrame = 0
        while (startFrame < totalFrames) {
            if (frameRms(pcm, startFrame * FRAME_SAMPLES) >= AudioLevel.VOICE_THRESHOLD) break
            startFrame++
        }
        if (startFrame == 0) return pcm
        val startByte = startFrame * FRAME_SAMPLES * 2
        if (startByte >= pcm.size) return pcm
        return pcm.copyOfRange(startByte, pcm.size)
    }

    fun normalizePeak(pcm: ByteArray): ByteArray {
        if (pcm.size < 2) return pcm
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val s = if (sample and 0x8000 != 0) sample or -0x10000 else sample
            peak = maxOf(peak, kotlin.math.abs(s))
            i += 2
        }
        if (peak == 0 || peak >= MIN_PEAK_TO_BOOST) return pcm
        val scale = TARGET_PEAK.toDouble() / peak
        val out = ByteArray(pcm.size)
        i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val s = if (sample and 0x8000 != 0) sample or -0x10000 else sample
            val scaled = (s * scale).toInt().coerceIn(-32_767, 32_767)
            out[i] = (scaled and 0xFF).toByte()
            out[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    fun addTailPadding(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
        val padBytes = sampleRate * 2 * TAIL_PADDING_MS / 1000
        if (padBytes <= 0) return pcm
        return pcm + ByteArray(padBytes)
    }

    private fun frameRms(pcm: ByteArray, startSample: Int): Double {
        val startByte = startSample * 2
        val frameBytes = minOf(FRAME_SAMPLES * 2, pcm.size - startByte)
        if (frameBytes <= 0) return 0.0
        return AudioLevel.rmsPcm16Le(pcm, startByte, frameBytes)
    }
}
