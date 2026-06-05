package dev.erik.voiceinput

import android.content.Context

object VoicePipeline {
    private val stt = GrokSttClient()

    fun transcribe(context: Context, pcm: ByteArray): String {
        val apiKey =
            Prefs.getApiKey(context)
                ?: throw IllegalStateException("Missing xAI API key — open Grok Voice Input settings")
        if (pcm.size < 3200) {
            throw IllegalStateException("Recording too short")
        }
        val trimmed = AudioSilenceTrimmer.trimPcm16Le(pcm)
        val wav = WavEncoder.encodePcm16Mono(trimmed)
        return stt.transcribe(
            apiKey = apiKey,
            wavBytes = wav,
            language = Prefs.getLanguage(context),
        )
    }
}