package dev.erik.voiceinput

import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class GrokRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorder = PcmRecorder()
    private val activeCallback = AtomicReference<Callback?>(null)
    private val stt = GrokSttClient()

    override fun onDestroy() {
        recorder.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        activeCallback.set(listener)
        try {
            listener.readyForSpeech(Bundle.EMPTY)
            listener.beginningOfSpeech()
        } catch (e: RemoteException) {
            Log.e(TAG, "callback failed", e)
            return
        }

        val apiKey = Prefs.getApiKey(this)
        if (apiKey.isNullOrBlank()) {
            sendError(listener, SpeechRecognizer.ERROR_CLIENT, "Missing xAI API key")
            return
        }

        if (!recorder.start()) {
            sendError(listener, SpeechRecognizer.ERROR_AUDIO, "Could not start microphone")
            return
        }
    }

    override fun onStopListening(listener: Callback) {
        val callback = activeCallback.getAndSet(null) ?: listener
        val pcm = recorder.stop()
        if (pcm.size < 3200) {
            sendError(callback, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "Recording too short")
            return
        }

        val apiKey =
            Prefs.getApiKey(this)
                ?: run {
                    sendError(callback, SpeechRecognizer.ERROR_CLIENT, "Missing xAI API key")
                    return
                }

        scope.launch {
            try {
                try {
                    callback.endOfSpeech()
                } catch (e: RemoteException) {
                    Log.e(TAG, "endOfSpeech failed", e)
                }
                val trimmed = AudioSilenceTrimmer.trimPcm16Le(pcm)
                val wav = WavEncoder.encodePcm16Mono(trimmed)
                val text =
                    stt.transcribe(
                        apiKey = apiKey,
                        wavBytes = wav,
                        language = Prefs.getLanguage(this@GrokRecognitionService),
                    )
                val results = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(text),
                    )
                }
                callback.results(results)
            } catch (e: Exception) {
                Log.e(TAG, "transcription failed", e)
                sendError(callback, SpeechRecognizer.ERROR_SERVER, e.message ?: "STT failed")
            }
        }
    }

    override fun onCancel(listener: Callback) {
        activeCallback.set(null)
        recorder.cancel()
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: RemoteException) {
            Log.e(TAG, "cancel callback failed", e)
        }
    }

    private fun sendError(listener: Callback, code: Int, message: String) {
        Log.w(TAG, "recognition error $code: $message")
        try {
            listener.error(code)
        } catch (e: RemoteException) {
            Log.e(TAG, "error callback failed", e)
        }
    }

    companion object {
        private const val TAG = "GrokRecognitionService"
    }
}