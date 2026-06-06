package dev.erik.voiceinput

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var transcribing = false
    private var inputView: View? = null

    override fun onDestroy() {
        recorder.cancel()
        scope.cancel()
        super.onDestroy()
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

        view.findViewById<ImageButton>(R.id.cancel).setOnClickListener {
            cancelAndReturnToKeyboard()
        }

        view.findViewById<View>(R.id.stop_button).setOnClickListener {
            if (!transcribing && recorder.isRecording()) {
                finishAndTranscribe()
            }
        }

        recorder.onLevel = { rms, voice ->
            voiceCircle?.setVoiceLevel(AudioLevel.normalizedLevel(rms), voice)
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
            voiceCircle?.setMode(VoiceLevelCircleView.Mode.IDLE)
            setStatus(getString(R.string.ime_mic_error))
            Toast.makeText(this, R.string.ime_mic_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        transcribing = false
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.RECORDING)
        setStatus(getString(R.string.ime_listening))
        if (!recorder.start()) {
            voiceCircle?.setMode(VoiceLevelCircleView.Mode.IDLE)
            setStatus(getString(R.string.ime_mic_error))
        }
    }

    private fun cancelAndReturnToKeyboard() {
        if (transcribing) return
        recorder.cancel()
        returnToKeyboard()
    }

    private fun finishAndTranscribe() {
        if (transcribing) return
        transcribing = true
        recorder.onLevel = null
        val pcm = recorder.stop()
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.TRANSCRIBING)
        setStatus(getString(R.string.ime_transcribing))

        scope.launch {
            try {
                val text =
                    withContext(Dispatchers.IO) {
                        VoicePipeline.transcribe(this@GrokVoiceInputMethodService, pcm)
                    }
                currentInputConnection?.commitText("$text ", 1)
                returnToKeyboard()
            } catch (e: Exception) {
                Log.e(TAG, "transcription failed", e)
                transcribing = false
                voiceCircle?.setMode(VoiceLevelCircleView.Mode.RECORDING)
                setStatus(e.message ?: getString(R.string.ime_failed))
                startRecordingSafely()
            }
        }
    }

    private fun returnToKeyboard() {
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