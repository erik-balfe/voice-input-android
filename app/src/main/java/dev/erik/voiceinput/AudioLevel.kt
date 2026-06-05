package dev.erik.voiceinput

import kotlin.math.abs
import kotlin.math.sqrt

object AudioLevel {
    const val VOICE_THRESHOLD = 350.0

    fun rmsPcm16Le(pcm: ByteArray, offset: Int, length: Int): Double {
        val sampleCount = length / 2
        if (sampleCount == 0) return 0.0
        var sum = 0.0
        var i = 0
        while (i < sampleCount) {
            val idx = offset + i * 2
            if (idx + 1 >= pcm.size) break
            val lo = pcm[idx].toInt() and 0xFF
            val hi = pcm[idx + 1].toInt()
            val sample = (hi shl 8) or lo
            val s = if (sample and 0x8000 != 0) sample or -0x10000 else sample
            sum += abs(s).toDouble()
            i++
        }
        return sum / sampleCount
    }

    fun normalizedLevel(rms: Double): Float =
        (rms / 4000.0).toFloat().coerceIn(0f, 1f)

    fun isVoice(rms: Double): Boolean = rms >= VOICE_THRESHOLD
}