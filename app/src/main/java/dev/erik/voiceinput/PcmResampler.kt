package dev.erik.voiceinput

/**
 * Linear resample mono PCM16 LE to a target rate.
 * Used only to match cosmic-scribe (16 kHz) when the device delivers another rate.
 */
object PcmResampler {
    const val TARGET_HZ = 16_000

    fun to16kMonoLe(pcm: ByteArray, sampleRate: Int): ByteArray {
        if (sampleRate <= 0 || pcm.size < 2) return pcm
        if (sampleRate == TARGET_HZ) return pcm
        val inSamples = pcm.size / 2
        if (inSamples < 2) return pcm
        val outSamples =
            ((inSamples.toLong() * TARGET_HZ) / sampleRate)
                .toInt()
                .coerceAtLeast(1)
        val out = ByteArray(outSamples * 2)
        var i = 0
        while (i < outSamples) {
            val src = i.toDouble() * sampleRate / TARGET_HZ
            val i0 = src.toInt().coerceIn(0, inSamples - 1)
            val i1 = (i0 + 1).coerceAtMost(inSamples - 1)
            val t = (src - i0).coerceIn(0.0, 1.0)
            val s0 = readSample(pcm, i0)
            val s1 = readSample(pcm, i1)
            val s = (s0 * (1.0 - t) + s1 * t).toInt().coerceIn(-32768, 32767)
            writeSample(out, i, s)
            i++
        }
        return out
    }

    private fun readSample(pcm: ByteArray, index: Int): Int {
        val o = index * 2
        val lo = pcm[o].toInt() and 0xFF
        val hi = pcm[o + 1].toInt()
        val sample = (hi shl 8) or lo
        return if (sample and 0x8000 != 0) sample or -0x10000 else sample
    }

    private fun writeSample(pcm: ByteArray, index: Int, value: Int) {
        val o = index * 2
        pcm[o] = (value and 0xFF).toByte()
        pcm[o + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
