package dev.erik.voiceinput

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time AAC-LC encode while PCM is captured.
 *
 * On [finish], only flushes EOS + muxer close — typically tens of ms, not seconds.
 * Format: mono AAC in M4A (~48 kbps), well supported by xAI STT and Android MediaCodec HW.
 */
class ProgressiveAacEncoder(
    private val sampleRate: Int,
    private val bitRate: Int = AacEncoder.BIT_RATE,
) {
    private val mime = MediaFormat.MIMETYPE_AUDIO_AAC
    private val timeoutUs = 5_000L
    private val bytesPerSample = 2 // mono 16-bit

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var outFile: File? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var presentationUs = 0L
    private val started = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    /** Pending PCM if codec input buffers are briefly full. */
    private val pending = ByteArrayOutputStream()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val format =
            MediaFormat.createAudioFormat(mime, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
        val c = MediaCodec.createEncoderByType(mime)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        codec = c

        val f = File.createTempFile("stt-live-", ".m4a")
        outFile = f
        muxer = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        DiagLog.i(
            "aac",
            "progressive start",
            "sr" to sampleRate,
            "bitRate" to bitRate,
            "file" to f.name,
        )
    }

    /**
     * Feed a chunk of mono PCM16 LE. Safe to call from the capture thread.
     * Non-blocking enough for real-time: drains output each call; queues leftover PCM.
     */
    @Synchronized
    fun feedPcm(data: ByteArray, offset: Int, length: Int) {
        if (!started.get() || finished.get() || length <= 0) return
        val c = codec ?: return
        // Append to pending (handles partial previous feeds)
        pending.write(data, offset, length)
        pumpInput(c, endOfStream = false)
        drainOutput(c)
    }

    /**
     * Finalize encoding. Returns M4A bytes or throws.
     * Should be called once after last [feedPcm].
     */
    @Synchronized
    fun finish(): GrokSttClient.AudioUpload {
        if (!started.get()) throw IllegalStateException("encoder not started")
        if (!finished.compareAndSet(false, true)) {
            val f = outFile ?: throw IllegalStateException("no output")
            return GrokSttClient.AudioUpload(f.readBytes(), "recording.m4a", "audio/mp4")
        }
        val c = codec ?: throw IllegalStateException("no codec")
        val t0 = System.nanoTime()
        try {
            pumpInput(c, endOfStream = true)
            // Drain until output EOS
            var guard = 0
            while (guard++ < 500) {
                if (drainOutput(c, waitForEos = true)) break
            }
        } finally {
            releaseCodecAndMuxer()
        }
        val f = outFile ?: throw IllegalStateException("no output file")
        val bytes = f.readBytes()
        val flushMs = (System.nanoTime() - t0) / 1_000_000L
        try {
            f.delete()
        } catch (_: Exception) {
        }
        outFile = null
        if (bytes.isEmpty()) throw IllegalStateException("empty m4a after progressive encode")
        DiagLog.i(
            "aac",
            "progressive finish",
            "m4aBytes" to bytes.size,
            "flushMs" to flushMs,
            "bitRate" to bitRate,
        )
        return GrokSttClient.AudioUpload(bytes, "recording.m4a", "audio/mp4")
    }

    @Synchronized
    fun cancel() {
        finished.set(true)
        try {
            releaseCodecAndMuxer()
        } catch (_: Exception) {
        }
        outFile?.delete()
        outFile = null
        pending.reset()
    }

    private fun pumpInput(c: MediaCodec, endOfStream: Boolean) {
        var eosQueued = false
        while (true) {
            val pendingBytes = pending.toByteArray()
            val remaining = pendingBytes.size
            val inIx = c.dequeueInputBuffer(timeoutUs)
            if (inIx < 0) {
                if (endOfStream && remaining == 0 && !eosQueued) {
                    // try harder for EOS
                    val retry = c.dequeueInputBuffer(50_000L)
                    if (retry >= 0) {
                        c.queueInputBuffer(
                            retry,
                            0,
                            0,
                            presentationUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        eosQueued = true
                    }
                }
                return
            }
            val inBuf = c.getInputBuffer(inIx) ?: return
            inBuf.clear()
            val capacity = inBuf.remaining()
            if (remaining <= 0) {
                if (endOfStream) {
                    c.queueInputBuffer(
                        inIx,
                        0,
                        0,
                        presentationUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    eosQueued = true
                } else {
                    c.queueInputBuffer(inIx, 0, 0, presentationUs, 0)
                }
                return
            }
            var toCopy = minOf(capacity, remaining)
            toCopy -= toCopy % bytesPerSample
            if (toCopy <= 0) {
                c.queueInputBuffer(inIx, 0, 0, presentationUs, 0)
                return
            }
            inBuf.put(pendingBytes, 0, toCopy)
            // shrink pending
            pending.reset()
            if (toCopy < remaining) {
                pending.write(pendingBytes, toCopy, remaining - toCopy)
            }
            val samples = toCopy / bytesPerSample
            c.queueInputBuffer(inIx, 0, toCopy, presentationUs, 0)
            presentationUs += samples * 1_000_000L / sampleRate
            if (pending.size() == 0 && !endOfStream) return
            if (pending.size() == 0 && endOfStream) {
                // loop to queue EOS
                continue
            }
        }
    }

    /** @return true if output EOS seen */
    private fun drainOutput(c: MediaCodec, waitForEos: Boolean = false): Boolean {
        val info = MediaCodec.BufferInfo()
        val wait = if (waitForEos) 20_000L else timeoutUs
        while (true) {
            val outIx = c.dequeueOutputBuffer(info, wait)
            when {
                outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val m = muxer ?: return false
                    if (muxerStarted) throw IllegalStateException("muxer format changed twice")
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }
                outIx >= 0 -> {
                    val outBuf = c.getOutputBuffer(outIx)
                    if (outBuf != null && info.size > 0 && muxerStarted &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        muxer?.writeSampleData(trackIndex, outBuf, info)
                    }
                    c.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return true
                    }
                }
            }
        }
    }

    private fun releaseCodecAndMuxer() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        if (muxerStarted) {
            try {
                muxer?.stop()
            } catch (_: Exception) {
            }
        }
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        muxerStarted = false
    }
}
