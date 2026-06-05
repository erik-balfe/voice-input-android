package dev.erik.voiceinput

import android.inputmethodservice.InputMethodService
import android.view.MotionEvent
import android.view.View
import android.widget.Button
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

    override fun onDestroy() {
        recorder.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.voice_input_ime, null)
        statusView = view.findViewById(R.id.status)
        val mic = view.findViewById<Button>(R.id.mic)
        view.findViewById<Button>(R.id.back_to_keyboard).setOnClickListener {
            requestHideSelf(0)
            switchToPreviousInputMethod()
        }

        mic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    setStatus(getString(R.string.ime_listening))
                    if (!recorder.start()) {
                        setStatus(getString(R.string.ime_mic_error))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val pcm = recorder.stop()
                    setStatus(getString(R.string.ime_transcribing))
                    scope.launch {
                        try {
                            val text =
                                withContext(Dispatchers.IO) {
                                    VoicePipeline.transcribe(this@GrokVoiceInputMethodService, pcm)
                                }
                            currentInputConnection?.commitText("$text ", 1)
                            setStatus(getString(R.string.ime_done))
                        } catch (e: Exception) {
                            setStatus(e.message ?: getString(R.string.ime_failed))
                        }
                    }
                }
            }
            true
        }
        return view
    }

    private fun setStatus(text: String) {
        statusView?.text = text
    }
}