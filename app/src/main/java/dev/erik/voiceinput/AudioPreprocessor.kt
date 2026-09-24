package dev.erik.voiceinput

import kotlin.math.abs

/**
 * Upload-only PCM trim. [VoicePipeline] drops long stretches that are certainly
 * not speech, then encodes that PCM for the API. No gain, denoise, or normalize.
 *
 * [trimLeadingSilence], [normalizePeak], and [addTailPadding] are legacy helpers
 * kept for older tests. They are not on the upload path.
 */
object AudioPreprocessor {
    /** Keep a run only when it is at least this long. Shorter gaps stay (breaths, punctuation). */
    const val MIN_DROP_MS = 1_800

    /** Audio kept on each side of a removed stretch so onsets and endings are not clipped. */
    const val PAD_MS = 350

    /**
     * Mean absolute sample at or above this is speech.
     * Just under it is uncertain and is kept.
     */
    const val SPEECH_KEEP_MEAN_ABS = 180.0

    /** At or below this, and with no spike, a frame is certainly not speech. */
    const val CERTAIN_NON_SPEECH_MEAN_ABS = 40.0

    /** A spike above this keeps the frame even when the mean is tiny. */
    const val CERTAIN_NON_SPEECH_PEAK = 160

    /** Net audio removed must reach this or the original buffer is sent. */
    const val MIN_SAVINGS_MS = 1_000

    /** Never hand the API a stub when the take may contain speech. */
    const val MIN_KEEP_MS = 400

    private const val FRAME_MS = 20
    private const val FRAME_SAMPLES = 320
    private const val TAIL_PADDING_MS = 300
    private const val TARGET_PEAK = 28_000
    private const val MIN_PEAK_TO_BOOST = 12_000

    private const val KIND_SILENCE = 0
    private const val KIND_UNCERTAIN = 1
    private const val KIND_SPEECH = 2

    /**
     * Drop certain non-speech runs longer than [MIN_DROP_MS], leaving [PAD_MS] on each side.
     * Returns the same [pcm] instance when the detector is unsure, nothing qualifies,
     * or the cut would save little.
     */
    fun prepareForStt(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
        if (sampleRate <= 0 || pcm.size < 4) return pcm
        val sampleCount = pcm.size / 2
        val frameSamples = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
        val durationMs = sampleCount * 1000L / sampleRate
        if (durationMs < MIN_DROP_MS) return pcm

        val frameCount = sampleCount / frameSamples
        if (frameCount == 0) return pcm

        val kind = IntArray(frameCount)
        var speechFrames = 0
        for (frame in 0 until frameCount) {
            val (mean, peak) = frameMeanPeak(pcm, frame * frameSamples, frameSamples)
            val k =
                when {
                    mean >= SPEECH_KEEP_MEAN_ABS -> KIND_SPEECH
                    mean <= CERTAIN_NON_SPEECH_MEAN_ABS && peak <= CERTAIN_NON_SPEECH_PEAK ->
                        KIND_SILENCE
                    else -> KIND_UNCERTAIN
                }
            kind[frame] = k
            if (k == KIND_SPEECH) speechFrames++
        }
        // No confident speech: do not guess. Quiet or unclear audio stays whole.
        if (speechFrames == 0) return pcm

        val minDropSamples = sampleRate.toLong() * MIN_DROP_MS / 1000L
        val padSamples = sampleRate * PAD_MS / 1000
        val drops = ArrayList<Pair<Int, Int>>(4)
        var frame = 0
        while (frame < frameCount) {
            if (kind[frame] != KIND_SILENCE) {
                frame++
                continue
            }
            val startFrame = frame
            while (frame < frameCount && kind[frame] == KIND_SILENCE) frame++
            val startSample = startFrame * frameSamples
            val endSample = frame * frameSamples
            val runSamples = (endSample - startSample).toLong()
            if (runSamples < minDropSamples) continue
            val dropStart = startSample + padSamples
            val dropEnd = endSample - padSamples
            if (dropEnd > dropStart) drops.add(dropStart to dropEnd)
        }
        if (drops.isEmpty()) return pcm

        var droppedSamples = 0L
        for ((start, end) in drops) droppedSamples += (end - start).toLong()
        val savedMs = droppedSamples * 1000L / sampleRate
        if (savedMs < MIN_SAVINGS_MS) return pcm

        val keptSamples = sampleCount - droppedSamples
        if (keptSamples <= 0L || keptSamples * 1000L / sampleRate < MIN_KEEP_MS) return pcm

        val out = ByteArray((keptSamples * 2).toInt())
        var cursor = 0
        var outSample = 0
        for ((dropStart, dropEnd) in drops) {
            outSample = copySamples(pcm, cursor, dropStart, out, outSample)
            cursor = dropEnd
        }
        outSample = copySamples(pcm, cursor, sampleCount, out, outSample)
        if (outSample * 2 != out.size) return pcm
        return out
    }

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
            peak = maxOf(peak, abs(s))
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

    private fun frameMeanPeak(pcm: ByteArray, startSample: Int, samples: Int): Pair<Double, Int> {
        var sum = 0.0
        var peak = 0
        var n = 0
        var sample = startSample
        val end = startSample + samples
        while (sample < end) {
            val byte = sample * 2
            if (byte + 1 >= pcm.size) break
            val value = abs(readSample(pcm, byte))
            sum += value
            if (value > peak) peak = value
            n++
            sample++
        }
        if (n == 0) return 0.0 to 0
        return (sum / n) to peak
    }

    private fun readSample(pcm: ByteArray, byte: Int): Int {
        val lo = pcm[byte].toInt() and 0xFF
        val hi = pcm[byte + 1].toInt()
        val sample = (hi shl 8) or lo
        return if (sample and 0x8000 != 0) sample or -0x10000 else sample
    }

    private fun copySamples(
        pcm: ByteArray,
        startSample: Int,
        endSample: Int,
        out: ByteArray,
        outSample: Int,
    ): Int {
        if (endSample <= startSample) return outSample
        val src = startSample * 2
        val dst = outSample * 2
        val bytes = (endSample - startSample) * 2
        System.arraycopy(pcm, src, out, dst, bytes)
        return outSample + (endSample - startSample)
    }

    private fun frameRms(pcm: ByteArray, startSample: Int): Double {
        val startByte = startSample * 2
        val frameBytes = minOf(FRAME_SAMPLES * 2, pcm.size - startByte)
        if (frameBytes <= 0) return 0.0
        return AudioLevel.rmsPcm16Le(pcm, startByte, frameBytes)
    }
}
