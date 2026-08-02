package dev.erik.voiceinput

import android.content.Context
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cheap PCM health stats for logs.
 * Optional [saveLastWav] only when verbose diagnostics is on (not the product path).
 * Product storage is compressed M4A; see docs/REQUIREMENTS.md.
 */
object AudioDiagnostics {
    data class Stats(
        val samples: Int,
        val peakAbs: Int,
        val meanAbs: Double,
        val rms: Double,
        val zeroFraction: Double,
        val zeroCrossingsPerSec: Double,
        val clippedFraction: Double,
    ) {
        fun logFields(): Array<Pair<String, Any?>> =
            arrayOf(
                "samples" to samples,
                "peak" to peakAbs,
                "meanAbs" to "%.1f".format(meanAbs),
                "rms" to "%.1f".format(rms),
                "zeroFrac" to "%.3f".format(zeroFraction),
                "zcr" to "%.0f".format(zeroCrossingsPerSec),
                "clipFrac" to "%.4f".format(clippedFraction),
            )
    }

    fun analyze(pcm: ByteArray, sampleRate: Int): Stats {
        val n = pcm.size / 2
        if (n == 0) {
            return Stats(0, 0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
        var peak = 0
        var sumAbs = 0.0
        var sumSq = 0.0
        var zeros = 0
        var clipped = 0
        var zc = 0
        var prev = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val s = if (sample and 0x8000 != 0) sample or -0x10000 else sample
            val a = abs(s)
            peak = maxOf(peak, a)
            sumAbs += a
            sumSq += s.toDouble() * s
            if (a < 8) zeros++
            if (a >= 32_000) clipped++
            if (i >= 2 && ((prev >= 0 && s < 0) || (prev < 0 && s >= 0))) zc++
            prev = s
            i += 2
        }
        val durSec = n.toDouble() / sampleRate.coerceAtLeast(1)
        return Stats(
            samples = n,
            peakAbs = peak,
            meanAbs = sumAbs / n,
            rms = sqrt(sumSq / n),
            zeroFraction = zeros.toDouble() / n,
            zeroCrossingsPerSec = if (durSec > 0) zc / durSec else 0.0,
            clippedFraction = clipped.toDouble() / n,
        )
    }

    fun lastClipFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "diag").apply { mkdirs() }, "last.wav")

    fun saveLastWav(context: Context, wav: ByteArray): File {
        val f = lastClipFile(context)
        f.writeBytes(wav)
        DiagLog.i("audio", "saved last.wav", "bytes" to wav.size, "path" to f.absolutePath)
        return f
    }
}
