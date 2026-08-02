package dev.erik.voiceinput

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Encode mono PCM16 LE → AAC in an M4A container.
 * ~32–48 kbps speech: ~8–12× smaller than 16 kHz WAV, still excellent for STT.
 *
 * xAI STT accepts M4A/AAC. Falls back to caller if encoding fails.
 */
object AacEncoder {
    /** Speech bitrate — quality stays high; size drops a lot vs WAV. */
    const val BIT_RATE = 48_000
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val TIMEOUT_US = 10_000L

    data class Result(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String,
        val bitRate: Int,
    )

    /**
     * @param pcm mono PCM16 little-endian
     * @param sampleRate Hz (16000 recommended)
     */
    fun encodePcm16MonoToM4a(
        pcm: ByteArray,
        sampleRate: Int,
        bitRate: Int = BIT_RATE,
    ): Result {
        require(pcm.size >= 2) { "empty pcm" }
        require(sampleRate > 0) { "bad sample rate" }

        val tmp = File.createTempFile("stt-aac-", ".m4a")
        try {
            encodeToFile(pcm, sampleRate, bitRate, tmp)
            val out = tmp.readBytes()
            if (out.isEmpty()) throw IllegalStateException("empty m4a output")
            return Result(
                bytes = out,
                mimeType = "audio/mp4",
                fileName = "recording.m4a",
                bitRate = bitRate,
            )
        } finally {
            tmp.delete()
        }
    }

    private fun encodeToFile(
        pcm: ByteArray,
        sampleRate: Int,
        bitRate: Int,
        outFile: File,
    ) {
        val format =
            MediaFormat.createAudioFormat(MIME, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

        val codec = MediaCodec.createEncoderByType(MIME)
        var muxer: MediaMuxer? = null
        var track = -1
        var muxerStarted = false

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            var pcmOffset = 0
            var inputDone = false
            var outputDone = false
            // presentation time in microseconds
            var presentationUs = 0L
            val bytesPerSample = 2 // mono 16-bit

            while (!outputDone) {
                if (!inputDone) {
                    val inIx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIx >= 0) {
                        val inBuf = codec.getInputBuffer(inIx)!!
                        inBuf.clear()
                        val capacity = inBuf.remaining()
                        val remaining = pcm.size - pcmOffset
                        if (remaining <= 0) {
                            codec.queueInputBuffer(
                                inIx,
                                0,
                                0,
                                presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val toCopy = minOf(capacity, remaining)
                            // Align to sample boundary
                            val aligned = toCopy - (toCopy % bytesPerSample)
                            if (aligned <= 0) {
                                codec.queueInputBuffer(inIx, 0, 0, presentationUs, 0)
                            } else {
                                inBuf.put(pcm, pcmOffset, aligned)
                                pcmOffset += aligned
                                val samples = aligned / bytesPerSample
                                codec.queueInputBuffer(inIx, 0, aligned, presentationUs, 0)
                                presentationUs += samples * 1_000_000L / sampleRate
                            }
                        }
                    }
                }

                val outIx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw IllegalStateException("format changed twice")
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && muxerStarted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            try {
                codec.release()
            } catch (_: Exception) {
            }
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
        }
    }
}
