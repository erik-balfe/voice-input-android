package dev.erik.voiceinput

import android.content.Context

/**
 * Mic capture → compressed M4A → STT.
 *
 * Product audio is **M4A only**. PCM exists only as a live buffer / rare encode fallback.
 * Prefer progressive M4A from capture; post-stop AAC only if progressive failed.
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

        val upload: GrokSttClient.AudioUpload = resolveUpload(context, clip, onPhase)

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
        )
        onPhase?.invoke("Done")
        return text
    }

    /**
     * Prefer progressive M4A; else post-stop AAC from PCM; WAV only as last resort
     * when both AAC paths fail (explicit encode failure, not a silent quality choice).
     */
    private fun resolveUpload(
        context: Context,
        clip: PcmClip,
        onPhase: ((String) -> Unit)?,
    ): GrokSttClient.AudioUpload {
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
            return progressive
        }

        onPhase?.invoke("Processing…")
        val pcm16k =
            if (clip.sampleRate == PcmResampler.TARGET_HZ) {
                clip.pcm
            } else {
                PcmResampler.to16kMonoLe(clip.pcm, clip.sampleRate)
            }
        maybeSaveDebugWav(context, clip, pcm16k)

        val encStart = System.nanoTime()
        return try {
            val aac = AacEncoder.encodePcm16MonoToM4a(pcm16k, PcmResampler.TARGET_HZ)
            val encMs = (System.nanoTime() - encStart) / 1_000_000L
            DiagLog.w(
                "pipeline",
                "post_stop_aac_fallback",
                "pcmBytes" to pcm16k.size,
                "m4aBytes" to aac.bytes.size,
                "encMs" to encMs,
            )
            GrokSttClient.AudioUpload(aac.bytes, aac.fileName, aac.mimeType)
        } catch (e: Exception) {
            DiagLog.w("pipeline", "AAC failed — WAV last resort", "err" to e.message)
            val wav = WavEncoder.encodePcm16Mono(pcm16k, PcmResampler.TARGET_HZ)
            GrokSttClient.AudioUpload(wav, "recording.wav", "audio/wav")
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
