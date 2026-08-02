package dev.erik.voiceinput

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Mono PCM16 capture + progressive AAC encode in parallel.
 * No silence gates / VoIP mode games.
 */
class PcmRecorder(
    private val context: Context? = null,
) {
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private val buffer = ByteArrayOutputStream()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeSourceName = AtomicReference("?")
    private val actualSampleRate = AtomicInteger(16_000)
    private val peakRms = AtomicLong(0)
    private var progressive: ProgressiveAacEncoder? = null

    var onLevel: ((rms: Double, isVoice: Boolean) -> Unit)? = null

    fun hasMicPermission(): Boolean {
        val ctx = context ?: return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun activeSourceName(): String = activeSourceName.get()

    fun sampleRate(): Int = actualSampleRate.get()

    fun peakRms(): Double = peakRms.get() / 1000.0

    fun voiceChunkRatio(): Double = 0.0

    fun isRecording(): Boolean = running.get()

    fun start(): Boolean {
        if (running.get()) return true
        if (!hasMicPermission()) {
            DiagLog.w("mic", "start denied — no RECORD_AUDIO permission")
            return false
        }
        DiagLog.init(context ?: return false)

        val timing = DiagLog.start("mic", "start", "path" to "raw+progressive_aac")

        val rates = intArrayOf(16_000, 48_000, 44_100, 32_000)
        var record: AudioRecord? = null
        var minBuf = 0
        for (sr in rates) {
            minBuf =
                AudioRecord.getMinBufferSize(
                    sr,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) continue
            record = openRecord(sr, minBuf) ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) break
            record.release()
            record = null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            timing.fail(IllegalStateException("could not open AudioRecord"))
            return false
        }

        val actualSr = record.sampleRate
        actualSampleRate.set(actualSr)
        DiagLog.i(
            "mic",
            "opened",
            "source" to activeSourceName.get(),
            "actualSr" to actualSr,
            "minBuf" to minBuf,
        )

        // Progressive AAC only at rates AAC encoders handle well (we request 16k first).
        progressive =
            try {
                ProgressiveAacEncoder(actualSr).also { it.start() }
            } catch (e: Exception) {
                DiagLog.w("mic", "progressive AAC unavailable", "err" to e.message)
                null
            }

        buffer.reset()
        peakRms.set(0)
        running.set(true)
        audioRecord = record
        try {
            record.startRecording()
        } catch (e: Exception) {
            running.set(false)
            progressive?.cancel()
            progressive = null
            record.release()
            audioRecord = null
            timing.fail(e)
            return false
        }

        timing.end(
            "source" to activeSourceName.get(),
            "actualSr" to actualSr,
            "progressiveAac" to (progressive != null),
            "recordingState" to record.recordingState,
        )

        val readSize = minBuf.coerceAtLeast(2048)
        thread =
            Thread {
                val chunk = ByteArray(readSize)
                while (running.get()) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n > 0) {
                        synchronized(buffer) {
                            buffer.write(chunk, 0, n)
                        }
                        try {
                            progressive?.feedPcm(chunk, 0, n)
                        } catch (e: Exception) {
                            DiagLog.w("aac", "feed failed", "err" to e.message)
                        }
                        val rms = AudioLevel.rmsPcm16Le(chunk, 0, n)
                        peakRms.updateAndGet { maxOf(it, (rms * 1000).toLong()) }
                        onLevel?.let { cb ->
                            mainHandler.post { cb(rms, AudioLevel.isVoice(rms)) }
                        }
                    } else if (n < 0) {
                        DiagLog.w("mic", "read error", "code" to n)
                    }
                }
            }.also {
                it.name = "PcmRecorder"
                it.start()
            }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(sampleRate: Int, minBuf: Int): AudioRecord? {
        if (!hasMicPermission()) return null
        val sources =
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
            )
        for (source in sources) {
            try {
                val rec = build(source, sampleRate, minBuf)
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    activeSourceName.set(MicMode.sourceName(source))
                    return rec
                }
                rec.release()
            } catch (e: Exception) {
                DiagLog.w(
                    "mic",
                    "open failed",
                    "source" to MicMode.sourceName(source),
                    "sr" to sampleRate,
                    "err" to e.message,
                )
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun build(source: Int, sampleRate: Int, minBuf: Int): AudioRecord {
        val buf = (minBuf * 4).coerceAtLeast(minBuf)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(buf)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buf,
            )
        }
    }

    fun stop(): PcmClip {
        val timing = DiagLog.start("mic", "stop")
        running.set(false)
        thread?.join(3000)
        thread = null
        releaseRecord()

        val pcm =
            synchronized(buffer) {
                buffer.toByteArray()
            }
        val sr = actualSampleRate.get()

        var encoded: GrokSttClient.AudioUpload? = null
        var flushMs = 0L
        val enc = progressive
        progressive = null
        if (enc != null) {
            val t0 = System.nanoTime()
            try {
                encoded = enc.finish()
            } catch (e: Exception) {
                DiagLog.w("aac", "progressive finish failed", "err" to e.message)
                try {
                    enc.cancel()
                } catch (_: Exception) {
                }
            }
            flushMs = (System.nanoTime() - t0) / 1_000_000L
        }

        val clip =
            PcmClip(
                pcm = pcm,
                sampleRate = sr,
                sourceName = activeSourceName.get(),
                encodedUpload = encoded,
                encodeFlushMs = flushMs,
            )
        val stats = AudioDiagnostics.analyze(pcm, sr)
        timing.end(
            "pcmBytes" to pcm.size,
            "durationMs" to clip.durationMs,
            "sampleRate" to sr,
            "source" to activeSourceName.get(),
            "m4aBytes" to (encoded?.bytes?.size ?: 0),
            "encodeFlushMs" to flushMs,
            *stats.logFields(),
        )
        return clip
    }

    fun cancel() {
        running.set(false)
        thread?.join(2000)
        thread = null
        releaseRecord()
        progressive?.cancel()
        progressive = null
        synchronized(buffer) {
            buffer.reset()
        }
    }

    private fun releaseRecord() {
        audioRecord?.let { r ->
            try {
                r.stop()
            } catch (_: IllegalStateException) {
            }
            r.release()
        }
        audioRecord = null
    }
}
