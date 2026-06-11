package dev.erik.voiceinput

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class PcmRecorder(
    private val context: Context? = null,
    private val sampleRate: Int = 16_000,
) {
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private val buffer = ByteArrayOutputStream()
    private val mainHandler = Handler(Looper.getMainLooper())

    var onLevel: ((rms: Double, isVoice: Boolean) -> Unit)? = null

    fun hasMicPermission(): Boolean {
        val ctx = context ?: return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun start(): Boolean {
        if (running.get()) return true
        if (!hasMicPermission()) return false

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) return false

        val record = createAudioRecord(minBuf) ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        buffer.reset()
        running.set(true)
        audioRecord = record
        try {
            record.startRecording()
        } catch (e: SecurityException) {
            running.set(false)
            record.release()
            audioRecord = null
            return false
        }

        thread =
            Thread {
                val chunk = ByteArray(minBuf)
                while (running.get()) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        synchronized(buffer) {
                            buffer.write(chunk, 0, read)
                        }
                        val rms = AudioLevel.rmsPcm16Le(chunk, 0, read)
                        val voice = AudioLevel.isVoice(rms)
                        onLevel?.let { cb ->
                            mainHandler.post { cb(rms, voice) }
                        }
                    }
                }
            }.also { it.start() }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(minBuf: Int): AudioRecord? {
        if (!hasMicPermission()) return null
        return try {
            // MIC = raw capture (closer to desktop arecord). VOICE_RECOGNITION applies heavy DSP.
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 8,
            )
        } catch (e: SecurityException) {
            null
        }
    }

    fun stop(): ByteArray {
        running.set(false)
        val reader = thread
        reader?.join(5000)
        thread = null
        releaseRecord()
        synchronized(buffer) {
            return buffer.toByteArray()
        }
    }

    fun cancel() {
        running.set(false)
        val reader = thread
        reader?.join(2000)
        thread = null
        releaseRecord()
        synchronized(buffer) {
            buffer.reset()
        }
    }

    private fun releaseRecord() {
        audioRecord?.let { record ->
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
        }
        audioRecord = null
    }

    fun isRecording(): Boolean = running.get()
}