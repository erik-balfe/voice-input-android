package dev.erik.voiceinput

import android.content.Context

/**
 * Mic capture → compressed M4A → STT.
 *
 * Product audio is **M4A only**. PCM exists only as a live buffer / rare encode fallback.
 * Prefer progressive M4A from capture when the PCM is sent whole.
 * A long certain non-speech cut re-encodes the trimmed PCM for the API only.
 * Full WAV is never written on the hot path (verbose diagnostics only).
 */
object VoicePipeline {
    const val MIN_DURATION_MS = 400L

    private val stt = GrokSttClient()

    fun transcribe(
        context: Context,
        clip: PcmClip,
        onPhase: ((String) -> Unit)? = null,
    ): String {
        DiagLog.init(context)
        val pipeline =
            DiagLog.start(
                "pipeline",
                "transcribe",
                "pcmBytes" to clip.pcm.size,
                "sampleRate" to clip.sampleRate,
                "durationMs" to clip.durationMs,
                "source" to clip.sourceName,
                "hasProgressiveM4a" to (clip.encodedUpload != null),
                "encodeFlushMs" to clip.encodeFlushMs,
            )

        onPhase?.invoke("Processing…")
        val authStart = System.nanoTime()
        var bearer =
            try {
                XaiOauth.resolveBearer(context)
            } catch (e: SttException) {
                pipeline.fail(e)
                throw e
            } catch (e: Exception) {
                val w = SttException.wrap(e)
                pipeline.fail(w)
                throw w
            }
        val authMs = (System.nanoTime() - authStart) / 1_000_000L
        val usedOauth = Prefs.getAuthPreference(context) == AuthPreference.OAUTH
        DiagLog.i(
            "pipeline",
            "credential",
            "mode" to XaiOauth.authModeLabel(context),
            "oauth" to usedOauth,
            "authMs" to authMs,
            "prefix" to bearer.take(8),
        )

        validateClip(clip)
        if (clip.pcm.isNotEmpty()) {
            val stats = AudioDiagnostics.analyze(clip.pcm, clip.sampleRate)
            DiagLog.i("pipeline", "pcm_raw", *stats.logFields())
        }

        val resolved = resolveUpload(context, clip, onPhase)
        val upload = resolved.upload

        val language = Prefs.getLanguage(context)
        onPhase?.invoke("Processing…")
        val text =
            try {
                try {
                    stt.transcribe(
                        apiKey = bearer,
                        audio = upload,
                        language = language,
                        onPhase = onPhase,
                    )
                } catch (e: Exception) {
                    if (usedOauth && isExpiredToken(e) && XaiOauth.isLoggedIn(context)) {
                        DiagLog.w("pipeline", "STT 401; one OAuth refresh + retry")
                        XaiOauth.clearBearerCache()
                        bearer = XaiOauth.forceRefresh(context)
                        stt.transcribe(
                            apiKey = bearer,
                            audio = upload,
                            language = language,
                            onPhase = onPhase,
                        )
                    } else {
                        throw e
                    }
                }
            } catch (e: Exception) {
                pipeline.fail(
                    e,
                    "lang" to language,
                    "uploadBytes" to upload.bytes.size,
                    "file" to upload.fileName,
                )
                throw e
            }

        pipeline.end(
            "textLen" to text.length,
            "preview" to text.take(80),
            "uploadBytes" to upload.bytes.size,
            "uploadFile" to upload.fileName,
            "pcmBytes" to clip.pcm.size,
            "authMs" to authMs,
            "encodeFlushMs" to clip.encodeFlushMs,
            "progressive" to (clip.encodedUpload != null),
            "trimmedSilence" to resolved.trimmed,
        )
        onPhase?.invoke("Done")
        return text
    }

    private data class ResolvedUpload(
        val upload: GrokSttClient.AudioUpload,
        /** True when the API bytes were encoded from trimmed PCM, not the stored take. */
        val trimmed: Boolean,
    )

    /**
     * Prefer progressive M4A when the take is unchanged. A real silence cut encodes
     * the trimmed PCM instead. Empty PCM (History retry) sends the stored original.
     */
    private fun resolveUpload(
        context: Context,
        clip: PcmClip,
        onPhase: ((String) -> Unit)?,
    ): ResolvedUpload {
        val pcm16k = pcmAt16k(clip)
        val forUpload =
            if (pcm16k.isNotEmpty() && Prefs.isTrimLongSilence(context)) {
                AudioPreprocessor.prepareForStt(pcm16k, PcmResampler.TARGET_HZ)
            } else {
                pcm16k
            }
        // Same instance means "send the original bytes".
        val trimmed = forUpload !== pcm16k

        if (!trimmed) {
            val progressive = clip.encodedUpload
            if (progressive != null && progressive.bytes.isNotEmpty()) {
                val ratio =
                    if (clip.pcm.isNotEmpty()) {
                        clip.pcm.size.toDouble() / progressive.bytes.size
                    } else {
                        0.0
                    }
                DiagLog.i(
                    "pipeline",
                    "use_progressive_m4a",
                    "pcmBytes" to clip.pcm.size,
                    "m4aBytes" to progressive.bytes.size,
                    "ratio" to "%.1fx".format(ratio),
                    "encodeFlushMs" to clip.encodeFlushMs,
                )
                maybeSaveDebugWav(context, clip)
                return ResolvedUpload(progressive, trimmed = false)
            }
        } else {
            DiagLog.i(
                "pipeline",
                "trimmed_long_silence",
                "pcmBytes" to pcm16k.size,
                "trimmedBytes" to forUpload.size,
                "savedBytes" to (pcm16k.size - forUpload.size),
            )
        }

        onPhase?.invoke("Processing…")
        val debugPcm = pcm16k.takeIf { it.isNotEmpty() }
        maybeSaveDebugWav(context, clip, debugPcm)

        val toEncode = if (forUpload.isNotEmpty()) forUpload else pcm16k
        val encStart = System.nanoTime()
        return try {
            val aac = AacEncoder.encodePcm16MonoToM4a(toEncode, PcmResampler.TARGET_HZ)
            val encMs = (System.nanoTime() - encStart) / 1_000_000L
            if (trimmed) {
                DiagLog.i(
                    "pipeline",
                    "upload_trimmed_m4a",
                    "pcmBytes" to toEncode.size,
                    "m4aBytes" to aac.bytes.size,
                    "encMs" to encMs,
                )
            } else {
                DiagLog.w(
                    "pipeline",
                    "post_stop_aac_fallback",
                    "pcmBytes" to toEncode.size,
                    "m4aBytes" to aac.bytes.size,
                    "encMs" to encMs,
                )
            }
            ResolvedUpload(
                GrokSttClient.AudioUpload(aac.bytes, aac.fileName, aac.mimeType),
                trimmed = trimmed,
            )
        } catch (e: Exception) {
            DiagLog.w("pipeline", "AAC failed — WAV last resort", "err" to e.message)
            val wav = WavEncoder.encodePcm16Mono(toEncode, PcmResampler.TARGET_HZ)
            ResolvedUpload(
                GrokSttClient.AudioUpload(wav, "recording.wav", "audio/wav"),
                trimmed = trimmed,
            )
        }
    }

    /** Full PCM at 16 kHz, or empty when the clip is M4A-only (History). */
    private fun pcmAt16k(clip: PcmClip): ByteArray {
        if (clip.pcm.isEmpty()) return clip.pcm
        return if (clip.sampleRate == PcmResampler.TARGET_HZ) {
            clip.pcm
        } else {
            PcmResampler.to16kMonoLe(clip.pcm, clip.sampleRate)
        }
    }

    /** Opt-in only: full WAV for share-from-settings debugging. */
    private fun maybeSaveDebugWav(
        context: Context,
        clip: PcmClip,
        pcm16kOverride: ByteArray? = null,
    ) {
        if (!Prefs.isVerboseDiag(context)) return
        try {
            val pcm16k =
                pcm16kOverride
                    ?: if (clip.sampleRate == PcmResampler.TARGET_HZ) {
                        clip.pcm
                    } else {
                        PcmResampler.to16kMonoLe(clip.pcm, clip.sampleRate)
                    }
            if (pcm16k.isEmpty()) return
            val wav = WavEncoder.encodePcm16Mono(pcm16k, PcmResampler.TARGET_HZ)
            AudioDiagnostics.saveLastWav(context, wav)
        } catch (e: Exception) {
            DiagLog.w("pipeline", "save last.wav failed", "err" to e.message)
        }
    }

    fun transcribe(
        context: Context,
        pcm: ByteArray,
        onPhase: ((String) -> Unit)? = null,
    ): String = transcribe(context, PcmClip(pcm, 16_000), onPhase)

    fun validateClip(clip: PcmClip) {
        if (clip.durationMs < MIN_DURATION_MS) {
            throw SttException("Recording too short — speak at least half a second")
        }
    }

    fun validatePcm(pcm: ByteArray) {
        if (pcm.size < 12_800) {
            throw SttException("Recording too short — speak at least half a second")
        }
    }

    private fun isExpiredToken(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val msg = cur.message.orEmpty()
            if (msg.contains("spending-limit", ignoreCase = true)) return false
            if (msg.contains("run out of credits", ignoreCase = true)) return false
            if (msg.contains("STT 401")) return true
            cur = cur.cause
        }
        return false
    }
}
