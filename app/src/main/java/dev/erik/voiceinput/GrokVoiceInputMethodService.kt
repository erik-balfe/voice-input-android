package dev.erik.voiceinput

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Auxiliary voice keyboard — shows in the same keyboard list as Whisper IME / similar apps.
 */
class GrokVoiceInputMethodService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = PcmRecorder(this)

    private var statusView: TextView? = null
    private var voiceCircle: VoiceLevelCircleView? = null
    private var retryButton: ImageButton? = null
    private var transcribing = false
    private var transcribeJob: Job? = null
    private var lastFailedPcm: ByteArray? = null
    private var inputView: View? = null

    override fun onDestroy() {
        transcribeJob?.cancel()
        recorder.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (!transcribing) {
            recorder.cancel()
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onCreateInputView(): View {
        return try {
            val view = layoutInflater.inflate(R.layout.voice_input_ime, null)
            inputView = view
            bindInputView(view)
            view.post { startRecordingSafely() }
            view
        } catch (e: Exception) {
            Log.e(TAG, "onCreateInputView failed", e)
            fallbackView(e)
        }
    }

    private fun bindInputView(view: View) {
        statusView = view.findViewById(R.id.status)
        voiceCircle = view.findViewById(R.id.voice_circle)
        retryButton = view.findViewById(R.id.retry)

        view.findViewById<ImageButton>(R.id.cancel).setOnClickListener {
            cancelAndReturnToKeyboard()
        }

        view.findViewById<View>(R.id.stop_button).setOnClickListener {
            when {
                transcribing -> Unit
                lastFailedPcm != null -> retryTranscription()
                recorder.isRecording() -> finishAndTranscribe()
            }
        }

        retryButton?.setOnClickListener {
            retryTranscription()
        }
    }

    private fun fallbackView(error: Exception): View {
        val view = layoutInflater.inflate(R.layout.voice_input_ime_fallback, null)
        inputView = view
        view.findViewById<TextView>(R.id.status)?.text =
            getString(R.string.ime_load_error, error.message ?: "unknown")
        view.findViewById<View>(R.id.cancel)?.setOnClickListener {
            returnToKeyboard()
        }
        return view
    }

    private fun startRecordingSafely() {
        try {
            startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            showIdleError(getString(R.string.ime_mic_error))
            Toast.makeText(this, R.string.ime_mic_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        transcribeJob?.cancel()
        transcribing = false
        lastFailedPcm = null
        hideRetry()
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.RECORDING)
        setStatus(getString(R.string.ime_listening))

        if (Prefs.getApiKey(this).isNullOrBlank()) {
            showIdleError(getString(R.string.api_key_missing))
            return
        }

        if (!recorder.start()) {
            showIdleError(getString(R.string.ime_mic_error))
        }
    }

    private fun cancelAndReturnToKeyboard() {
        transcribeJob?.cancel()
        transcribing = false
        lastFailedPcm = null
        recorder.cancel()
        returnToKeyboard()
    }

    private fun finishAndTranscribe() {
        if (transcribing) return
        recorder.onLevel = null
        val pcm = recorder.stop()
        transcribePcm(pcm)
    }

    private fun retryTranscription() {
        val pcm = lastFailedPcm ?: return
        transcribePcm(pcm)
    }

    private fun transcribePcm(pcm: ByteArray) {
        transcribeJob?.cancel()
        transcribing = true
        hideRetry()
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.TRANSCRIBING)
        setStatus(getString(R.string.ime_transcribing))

        transcribeJob =
            scope.launch {
                try {
                    val text =
                        withContext(Dispatchers.IO) {
                            VoicePipeline.transcribe(this@GrokVoiceInputMethodService, pcm)
                        }
                    val connection = currentInputConnection
                    if (connection == null) {
                        showTranscribeError(
                            pcm,
                            getString(R.string.ime_no_input_connection),
                        )
                        return@launch
                    }
                    connection.commitText("$text ", 1)
                    returnToKeyboard()
                } catch (e: Exception) {
                    Log.e(TAG, "transcription failed", e)
                    val msg =
                        when (e) {
                            is SttException -> e.userMessage
                            else -> SttException.wrap(e).userMessage
                        }
                    showTranscribeError(pcm, msg)
                }
            }
    }

    private fun showTranscribeError(pcm: ByteArray, message: String) {
        transcribing = false
        lastFailedPcm = pcm
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.IDLE)
        setStatus(message)
        showRetry()
    }

    private fun showIdleError(message: String) {
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.IDLE)
        setStatus(message)
        hideRetry()
    }

    private fun showRetry() {
        retryButton?.visibility = View.VISIBLE
    }

    private fun hideRetry() {
        retryButton?.visibility = View.GONE
    }

    private fun returnToKeyboard() {
        transcribing = false
        lastFailedPcm = null
        transcribeJob?.cancel()
        requestHideSelf(0)
        switchToPreviousInputMethod()
    }

    private fun setStatus(text: String) {
        statusView?.text = text
    }

    companion object {
        private const val TAG = "GrokVoiceIME"
    }
}