package dev.erik.voiceinput

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
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
 * Voice keyboard: listen → process → insert text.
 * Keep-worthy takes are always finalized to History (see docs/FEATURE_DESIGN.md).
 */
class GrokVoiceInputMethodService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = PcmRecorder(this)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var statusView: TextView? = null
    private var debugDetail: TextView? = null
    private var voiceCircle: VoiceLevelCircleView? = null
    private var retryButton: ImageButton? = null
    private var openAppButton: ImageButton? = null
    private var transcribing = false
    private var transcribeJob: Job? = null
    private var lastFailedClip: PcmClip? = null
    private var lastSessionId: String? = null
    private var inputView: View? = null
    private var showDebug = false
    private var lastRms = 0.0
    private var lastVoice = false
    private var listenStartedAt = 0L
    private var phaseLine = ""
    private var endingSession = false

    private val listenTicker =
        object : Runnable {
            override fun run() {
                if (!recorder.isRecording() || transcribing) return
                updateListenUi()
                mainHandler.postDelayed(this, 200)
            }
        }

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        DiagLog.i("ime", "onCreate")
    }

    override fun onDestroy() {
        DiagLog.i("ime", "onDestroy")
        mainHandler.removeCallbacks(listenTicker)
        if (recorder.isRecording() && !transcribing) {
            saveOnlyIfKeepWorthy(notify = false)
        }
        transcribeJob?.cancel()
        if (recorder.isRecording()) recorder.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        DiagLog.ui(
            "finishInputView",
            "finishingInput" to finishingInput,
            "transcribing" to transcribing,
            "recording" to recorder.isRecording(),
        )
        // Back / hide: stop + keep audio (do not discard keep-worthy takes).
        if (!transcribing && recorder.isRecording() && !endingSession) {
            saveOnlyIfKeepWorthy(notify = true)
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        DiagLog.ui(
            "startInputView",
            "restarting" to restarting,
            "package" to (info?.packageName ?: "?"),
            "inputType" to (info?.inputType ?: 0),
            "actionId" to (info?.imeOptions ?: 0),
        )
    }

    override fun onCreateInputView(): View {
        return try {
            val themed = withSystemNightMode()
            DiagLog.i(
                "ime",
                "inflate",
                "systemNight" to isSystemNightMode(),
                "localNight" to
                    ((resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES),
            )
            val view =
                android.view.LayoutInflater.from(themed)
                    .inflate(R.layout.voice_input_ime, null)
            inputView = view
            bindInputView(view)
            view.post { startRecordingSafely() }
            view
        } catch (e: Exception) {
            Log.e(TAG, "onCreateInputView failed", e)
            DiagLog.e("ime", "onCreateInputView failed", e)
            fallbackView(e)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        DiagLog.i(
            "ime",
            "configChanged",
            "systemNight" to isSystemNightMode(),
            "uiMode" to newConfig.uiMode,
        )
        setInputView(onCreateInputView())
    }

    private fun bindInputView(view: View) {
        statusView = view.findViewById(R.id.status)
        debugDetail = view.findViewById(R.id.debug_detail)
        voiceCircle = view.findViewById(R.id.voice_circle)
        retryButton = view.findViewById(R.id.retry)
        openAppButton = view.findViewById(R.id.open_app)
        showDebug = Prefs.isImeDebugOverlay(this)
        debugDetail?.visibility = if (showDebug) View.VISIBLE else View.GONE

        view.findViewById<ImageButton>(R.id.cancel).setOnClickListener {
            DiagLog.ui("cancel_tap")
            cancelAndReturnToKeyboard()
        }

        view.findViewById<View>(R.id.stop_button).setOnClickListener {
            DiagLog.ui(
                "stop_tap",
                "transcribing" to transcribing,
                "hasFailed" to (lastFailedClip != null),
                "recording" to recorder.isRecording(),
            )
            when {
                transcribing -> Unit
                lastFailedClip != null -> retryTranscription()
                recorder.isRecording() -> finishAndTranscribe()
            }
        }

        retryButton?.setOnClickListener {
            DiagLog.ui("retry_tap")
            retryTranscription()
        }

        openAppButton?.setOnClickListener {
            DiagLog.ui("open_app_tap")
            openHistoryApp()
        }

        statusView?.setOnLongClickListener {
            val snap = DiagLog.statusLine()
            DiagLog.i("ime", "status long-press", "snap" to snap)
            Toast.makeText(this, snap.take(120), Toast.LENGTH_LONG).show()
            true
        }
    }

    private fun fallbackView(error: Exception): View {
        val view =
            android.view.LayoutInflater.from(withSystemNightMode())
                .inflate(R.layout.voice_input_ime_fallback, null)
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
            DiagLog.e("ime", "startRecording failed", e)
            showIdleError(getString(R.string.ime_mic_error))
            Toast.makeText(this, R.string.ime_mic_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        DiagLog.beginSession("ime")
        transcribeJob?.cancel()
        transcribing = false
        endingSession = false
        lastFailedClip = null
        lastSessionId = null
        phaseLine = ""
        hideRetry()
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.RECORDING)
        setStatus(getString(R.string.ime_listening))

        if (!XaiOauth.hasAnyAuth(this)) {
            DiagLog.w("ime", "missing auth")
            showIdleError(getString(R.string.auth_missing))
            return
        }

        recorder.onLevel = { rms, voice ->
            lastRms = rms
            lastVoice = voice
            voiceCircle?.setVoiceLevel(AudioLevel.normalizedLevel(rms), voice)
        }

        if (!recorder.start()) {
            DiagLog.w("ime", "recorder.start failed")
            showIdleError(getString(R.string.ime_mic_error))
            return
        }
        listenStartedAt = System.currentTimeMillis()
        mainHandler.removeCallbacks(listenTicker)
        mainHandler.post(listenTicker)
        updateListenUi()
    }

    private fun updateListenUi() {
        val sec = (System.currentTimeMillis() - listenStartedAt) / 1000.0
        val mm = (sec.toLong() / 60)
        val ss = (sec.toLong() % 60)
        setStatus(getString(R.string.ime_listening_duration, mm, ss))
        if (!showDebug) return
        val line =
            buildString {
                append("sid=").append(DiagLog.currentSessionId())
                append("  t=").append("%.1fs".format(sec))
                append("  mic=").append(recorder.activeSourceName())
                append("  sr=").append(recorder.sampleRate())
                append('\n')
                append("rms=").append("%.3f".format(lastRms))
                append("  voice=").append(if (lastVoice) "yes" else "no")
                append("  peak=").append("%.3f".format(recorder.peakRms()))
                append("  mode=").append(Prefs.getMicMode(this@GrokVoiceInputMethodService).prefValue)
            }
        debugDetail?.text = line
        DiagLog.setStatus("Listening %.1fs".format(sec), line.replace('\n', ' '))
    }

    private fun cancelAndReturnToKeyboard() {
        if (transcribing) {
            // Leave IME but do not abort STT — History still gets ok/failed.
            returnToKeyboard(cancelJob = false)
            return
        }
        saveOnlyIfKeepWorthy(notify = true)
        returnToKeyboard()
    }

    private fun openHistoryApp() {
        if (recorder.isRecording() && !transcribing) {
            saveOnlyIfKeepWorthy(notify = true)
        }
        val intent =
            Intent(this, HistoryActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        startActivity(intent)
        returnToKeyboard()
    }

    /**
     * Finalize mic → if keep-worthy, write M4A + meta as [SessionStatus.CANCELLED_SAVED].
     * Never silently drops audio the user could recover.
     */
    private fun saveOnlyIfKeepWorthy(notify: Boolean) {
        if (endingSession) return
        if (!recorder.isRecording()) return
        endingSession = true
        mainHandler.removeCallbacks(listenTicker)
        recorder.onLevel = null
        val clip = recorder.stop()
        DiagLog.ui(
            "save_only",
            "pcmBytes" to clip.pcm.size,
            "durationMs" to clip.durationMs,
            "keep" to SessionAudio.shouldKeep(clip),
        )
        if (!SessionAudio.shouldKeep(clip)) {
            endingSession = false
            return
        }
        val meta = persistClip(clip, SessionStatus.CANCELLED_SAVED)
        endingSession = false
        if (meta != null && notify) {
            Toast.makeText(this, R.string.ime_saved_to_history, Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishAndTranscribe() {
        if (transcribing || endingSession) return
        endingSession = true
        mainHandler.removeCallbacks(listenTicker)
        recorder.onLevel = null
        val clip = recorder.stop()
        endingSession = false
        DiagLog.ui(
            "stop_record",
            "pcmBytes" to clip.pcm.size,
            "sampleRate" to clip.sampleRate,
            "durationMs" to clip.durationMs,
            "source" to clip.sourceName,
            "peakRms" to "%.3f".format(recorder.peakRms()),
        )
        if (!SessionAudio.shouldKeep(clip)) {
            showIdleError(getString(R.string.ime_too_short))
            return
        }
        val meta = persistClip(clip, SessionStatus.PENDING)
        lastSessionId = meta?.id
        transcribeClip(clip, sessionId = meta?.id)
    }

    private fun persistClip(clip: PcmClip, status: SessionStatus): RecordingMeta? {
        val bytes = SessionAudio.m4aBytes(clip) ?: return null
        val store = RecordingStore.fromContext(this)
        val pkg = currentInputEditorInfo?.packageName
        return try {
            store.save(
                m4aBytes = bytes,
                durationMs = clip.durationMs,
                status = status,
                language = Prefs.getLanguage(this),
                sampleRate = clip.sampleRate,
                sourcePackage = pkg,
                maxItems = Prefs.getHistoryMaxItems(this),
                maxBytes = Prefs.getHistoryMaxBytes(this),
            ).also {
                DiagLog.i(
                    "session",
                    "persisted",
                    "id" to (it?.id ?: "?"),
                    "status" to status.wire,
                    "m4aBytes" to bytes.size,
                    "durationMs" to clip.durationMs,
                )
            }
        } catch (e: Exception) {
            DiagLog.e("session", "persist failed", e)
            null
        }
    }

    private fun retryTranscription() {
        val clip = lastFailedClip ?: return
        DiagLog.ui("retry", "pcmBytes" to clip.pcm.size, "sr" to clip.sampleRate)
        DiagLog.beginSession("ime-retry")
        // Prefer existing session row; else create pending again.
        val sid =
            lastSessionId
                ?: persistClip(clip, SessionStatus.PENDING)?.id
        lastSessionId = sid
        if (sid != null) {
            RecordingStore.fromContext(this).updateStatus(sid, SessionStatus.PENDING)
        }
        transcribeClip(clip, sessionId = sid)
    }

    private fun transcribeClip(clip: PcmClip, sessionId: String?) {
        transcribeJob?.cancel()
        transcribing = true
        hideRetry()
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.TRANSCRIBING)
        setStatus(getString(R.string.ime_processing))
        phaseLine = "Processing…"
        updatePhaseOverlay("Processing…")

        val wallStart = System.currentTimeMillis()
        val store = RecordingStore.fromContext(this)
        transcribeJob =
            scope.launch {
                try {
                    val text =
                        withContext(Dispatchers.IO) {
                            VoicePipeline.transcribe(
                                this@GrokVoiceInputMethodService,
                                clip,
                            ) { phase ->
                                phaseLine = phase
                                mainHandler.post { updatePhaseOverlay(phase) }
                            }
                        }
                    val totalMs = System.currentTimeMillis() - wallStart
                    DiagLog.i(
                        "ime",
                        "transcribe ok",
                        "wallMs" to totalMs,
                        "chars" to text.length,
                        "preview" to text.take(80),
                        "sr" to clip.sampleRate,
                        "sessionId" to (sessionId ?: ""),
                    )
                    if (sessionId != null) {
                        withContext(Dispatchers.IO) { store.markOk(sessionId, text) }
                    }
                    updatePhaseOverlay("Insert text… total=${totalMs}ms")
                    val connection = currentInputConnection
                    if (connection == null) {
                        DiagLog.w("ime", "no InputConnection after STT")
                        showTranscribeError(
                            clip,
                            getString(R.string.ime_no_input_connection),
                            sessionId,
                        )
                        return@launch
                    }
                    val commitStart = System.nanoTime()
                    connection.commitText("$text ", 1)
                    val commitMs = (System.nanoTime() - commitStart) / 1_000_000L
                    DiagLog.i("ime", "commitText", "ms" to commitMs, "chars" to text.length)
                    returnToKeyboard()
                } catch (e: Exception) {
                    Log.e(TAG, "transcription failed", e)
                    DiagLog.e("ime", "transcription failed", e)
                    val msg =
                        when (e) {
                            is SttException -> e.userMessage
                            else -> SttException.wrap(e).userMessage
                        }
                    if (sessionId != null) {
                        withContext(Dispatchers.IO) { store.markFailed(sessionId, msg) }
                    }
                    showTranscribeError(clip, msg, sessionId)
                }
            }
    }

    private fun updatePhaseOverlay(phase: String) {
        val userFacing =
            when {
                phase.startsWith("Done") || phase.startsWith("Total") ->
                    getString(R.string.ime_processing)
                phase.startsWith("Retry") -> phase
                else -> getString(R.string.ime_processing)
            }
        setStatus(userFacing)
        if (!showDebug) return
        val line =
            buildString {
                append("sid=").append(DiagLog.currentSessionId())
                append("  phase=").append(phase)
                append('\n')
                append(DiagLog.lastDetailOnly().ifBlank { DiagLog.lastStatusOnly() })
            }
        debugDetail?.text = line
        statusView?.text = phase
    }

    private fun showTranscribeError(clip: PcmClip, message: String, sessionId: String? = lastSessionId) {
        transcribing = false
        lastFailedClip = clip
        lastSessionId = sessionId
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.IDLE)
        val withHint =
            if (sessionId != null) {
                "$message\n${getString(R.string.ime_failed_saved_hint)}"
            } else {
                message
            }
        setStatus(withHint)
        if (showDebug) {
            debugDetail?.text =
                "ERROR sid=${DiagLog.currentSessionId()}\nsr=${clip.sampleRate}\n$message\n${DiagLog.lastDetailOnly()}"
        }
        showRetry()
    }

    private fun showIdleError(message: String) {
        mainHandler.removeCallbacks(listenTicker)
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.IDLE)
        setStatus(message)
        if (showDebug) {
            debugDetail?.text = message
        }
        hideRetry()
    }

    private fun showRetry() {
        retryButton?.visibility = View.VISIBLE
    }

    private fun hideRetry() {
        retryButton?.visibility = View.GONE
    }

    private fun returnToKeyboard(cancelJob: Boolean = true) {
        DiagLog.ui("returnToKeyboard", "cancelJob" to cancelJob)
        mainHandler.removeCallbacks(listenTicker)
        if (cancelJob) {
            transcribing = false
            lastFailedClip = null
            transcribeJob?.cancel()
        }
        endingSession = false
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
