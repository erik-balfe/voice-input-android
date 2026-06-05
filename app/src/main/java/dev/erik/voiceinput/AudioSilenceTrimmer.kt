package dev.erik.voiceinput

import kotlin.math.abs

/**
 * Trims leading/trailing silence from 16-bit PCM mono audio using a simple RMS threshold.
 */
object AudioSilenceTrimmer {
    private const val FRAME_SAMPLES = 320 // 20 ms at 16 kHz
    private const val RMS_THRESHOLD = AudioLevel.VOICE_THRESHOLD

    fun trimPcm16Le(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
        if (pcm.size < 4) return pcm
        val sampleCount = pcm.size / 2
        var startFrame = 0
        var endFrame = (sampleCount + FRAME_SAMPLES - 1) / FRAME_SAMPLES - 1

        while (startFrame <= endFrame) {
            if (frameRms(pcm, startFrame * FRAME_SAMPLES) >= RMS_THRESHOLD) break
            startFrame++
        }
        while (endFrame >= startFrame) {
            if (frameRms(pcm, endFrame * FRAME_SAMPLES) >= RMS_THRESHOLD) break
            endFrame--
        }

        if (startFrame > endFrame) return pcm

        val startByte = startFrame * FRAME_SAMPLES * 2
        val endSample = minOf((endFrame + 1) * FRAME_SAMPLES, sampleCount)
        val endByte = endSample * 2
        if (startByte <= 0 && endByte >= pcm.size) return pcm
        return pcm.copyOfRange(startByte, endByte.coerceAtMost(pcm.size))
    }

    private fun frameRms(pcm: ByteArray, startSample: Int): Double {
        val startByte = startSample * 2
        val frameBytes = minOf(FRAME_SAMPLES * 2, pcm.size - startByte)
        if (frameBytes <= 0) return 0.0
        return AudioLevel.rmsPcm16Le(pcm, startByte, frameBytes)
    }
}