package dev.erik.voiceinput

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
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
    private val recorder = PcmRecorder()

    private var statusView: TextView? = null
    private var voiceCircle: VoiceLevelCircleView? = null
    private var transcribing = false

    override fun onDestroy() {
        recorder.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val themedContext =
            ContextThemeWrapper(this, R.style.Theme_VoiceInput).apply {
                applyOverrideConfiguration(Configuration(resources.configuration))
            }
        val view =
            layoutInflater.cloneInContext(themedContext).inflate(R.layout.voice_input_ime, null)
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

        startRecording()
        return view
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
                transcribing = false
                voiceCircle?.setMode(VoiceLevelCircleView.Mode.RECORDING)
                setStatus(e.message ?: getString(R.string.ime_failed))
                startRecording()
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
}