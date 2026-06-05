package dev.erik.voiceinput

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class PcmRecorder(
    private val sampleRate: Int = 16_000,
) {
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private val buffer = ByteArrayOutputStream()
    private val mainHandler = Handler(Looper.getMainLooper())

    var onLevel: ((rms: Double, isVoice: Boolean) -> Unit)? = null

    fun start(): Boolean {
        if (running.get()) return true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) return false

        val record =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        buffer.reset()
        running.set(true)
        audioRecord = record
        record.startRecording()

        thread =
            Thread {
                val chunk = ByteArray(minBuf)
                while (running.get()) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        buffer.write(chunk, 0, read)
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

    fun stop(): ByteArray {
        running.set(false)
        thread?.join(1500)
        thread = null
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        audioRecord = null
        return buffer.toByteArray()
    }

    fun cancel() {
        running.set(false)
        thread?.join(500)
        thread = null
        audioRecord?.release()
        audioRecord = null
        buffer.reset()
    }

    fun isRecording(): Boolean = running.get()
}