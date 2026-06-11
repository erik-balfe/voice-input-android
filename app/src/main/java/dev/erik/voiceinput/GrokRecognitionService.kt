package dev.erik.voiceinput

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GrokRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorder = PcmRecorder(this)
    private val activeCallback = AtomicReference<Callback?>(null)
    private val sessionGeneration = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onDestroy() {
        sessionGeneration.incrementAndGet()
        stopMicForeground()
        recorder.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        sessionGeneration.incrementAndGet()
        recorder.cancel()
        activeCallback.set(listener)
        try {
            listener.readyForSpeech(Bundle.EMPTY)
            listener.beginningOfSpeech()
        } catch (e: RemoteException) {
            Log.e(TAG, "callback failed", e)
            return
        }

        if (Prefs.getApiKey(this).isNullOrBlank()) {
            sendError(listener, SpeechRecognizer.ERROR_CLIENT, "Missing xAI API key")
            return
        }

        startMicForeground()
        if (!recorder.start()) {
            stopMicForeground()
            sendError(listener, SpeechRecognizer.ERROR_AUDIO, "Could not start microphone")
            return
        }
    }

    override fun onStopListening(listener: Callback) {
        val session = sessionGeneration.get()
        val callback = activeCallback.getAndSet(null) ?: listener
        val pcm = recorder.stop()
        stopMicForeground()

        try {
            VoicePipeline.validatePcm(pcm)
        } catch (e: SttException) {
            sendError(callback, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, e.userMessage)
            return
        }

        if (Prefs.getApiKey(this).isNullOrBlank()) {
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
                if (session != sessionGeneration.get()) return@launch
                val text = VoicePipeline.transcribe(this@GrokRecognitionService, pcm)
                if (session != sessionGeneration.get()) return@launch
                val results = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(text),
                    )
                }
                try {
                    callback.results(results)
                } catch (e: RemoteException) {
                    Log.e(TAG, "results callback failed", e)
                }
            } catch (e: Exception) {
                if (session != sessionGeneration.get()) return@launch
                Log.e(TAG, "transcription failed", e)
                val msg =
                    when (e) {
                        is SttException -> e.userMessage
                        else -> SttException.wrap(e).userMessage
                    }
                sendError(callback, SpeechRecognizer.ERROR_SERVER, msg)
            }
        }
    }

    override fun onCancel(listener: Callback) {
        sessionGeneration.incrementAndGet()
        activeCallback.set(null)
        recorder.cancel()
        stopMicForeground()
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

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        mgr.createNotificationChannel(channel)
    }

    private fun startMicForeground() {
        val notification = buildNotification(getString(R.string.notification_listening))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopMicForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val TAG = "GrokRecognitionService"
        private const val CHANNEL_ID = "voice_input_recognition"
        private const val NOTIFICATION_ID = 1
    }
}