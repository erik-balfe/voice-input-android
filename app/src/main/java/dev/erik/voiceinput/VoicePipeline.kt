package dev.erik.voiceinput

import android.content.Context

object VoicePipeline {
    /** 16 kHz mono PCM16: 32000 bytes/s → 500 ms minimum (matches desktop cosmic-scribe). */
    const val MIN_PCM_BYTES = 16_000

    private val stt = GrokSttClient()

    fun transcribe(context: Context, pcm: ByteArray): String {
        val apiKey =
            Prefs.getApiKey(context)
                ?: throw SttException("Add your xAI API key in Settings")
        validatePcm(pcm)
        val trimmed = AudioSilenceTrimmer.trimPcm16Le(pcm)
        validatePcm(trimmed)
        val wav = WavEncoder.encodePcm16Mono(trimmed)
        return stt.transcribe(
            apiKey = apiKey,
            wavBytes = wav,
            language = Prefs.getLanguage(context),
        )
    }

    fun validatePcm(pcm: ByteArray) {
        if (pcm.size < MIN_PCM_BYTES) {
            throw SttException("Recording too short — speak at least half a second")
        }
    }
}