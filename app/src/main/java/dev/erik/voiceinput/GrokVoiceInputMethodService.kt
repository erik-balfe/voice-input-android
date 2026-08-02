package dev.erik.voiceinput

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice keyboard UX:
 * - Center orb: start / pause / resume listen
 * - ✓ : finish take → STT + insert
 * - ↵ : new line / paragraph in the field
 * - ⌨️ : switch to typing keyboard
 * After success (default): ready at 0:00 paused — user starts when ready.
 */
class GrokVoiceInputMethodService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = PcmRecorder(this)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var statusView: TextView? = null
    private var debugDetail: TextView? = null
    private var hintView: TextView? = null
    private var voiceCircle: VoiceLevelCircleView? = null
    private var retryButton: ImageButton? = null
    private var doneButton: ImageButton? = null
    private var newlineButton: ImageButton? = null
    private var openAppButton: ImageButton? = null
    private var processProgress: ProgressBar? = null
    private var transcribing = false
    private var transcribeJob: Job? = null
    private var lastFailedClip: PcmClip? = null
    private var lastSessionId: String? = null
    private var inputView: View? = null
    private var showDebug = false
    private var lastRms = 0.0
    private var lastVoice = false
    private var listenStartedAt = 0L
    /** Accumulated paused wall-time so duration only counts active listening. */
    private var pausedAccumMs = 0L
    private var pauseStartedAt = 0L
    private var phaseLine = ""
    private var endingSession = false
    private var processEstimateMs = 2500L
    private var processStartedAt = 0L
    private var progressDisplay = 0f
    private var tipHideAt = 0L
    /** Waiting for user to start next take (0:00, mic off). */
    private var readyForNextTake = false

    private val listenTicker =
        object : Runnable {
            override fun run() {
                if (!recorder.isRecording() || transcribing) return
                updateListenUi()
                maybeHideTip()
                mainHandler.postDelayed(this, 50)
            }
        }

    private val processTicker =
        object : Runnable {
            override fun run() {
                if (!transcribing) return
                val elapsed = System.currentTimeMillis() - processStartedAt
                progressDisplay =
                    ProcessingProgress.smoothToward(
                        display = progressDisplay,
                        elapsedMs = elapsed,
                        estimateTotalMs = processEstimateMs,
                    )
                voiceCircle?.setProgress(progressDisplay)
                // Keep frozen timer — ring alone shows processing (no % text).
                mainHandler.postDelayed(this, 16)
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
        // Back / hide / swipe: stop + keep audio (do not discard keep-worthy takes).
        // Do not leave a half-dead UI — next onStartInputView must start a fresh session.
        if (!transcribing && recorder.isRecording() && !endingSession) {
            saveOnlyIfKeepWorthy(notify = true)
        }
        mainHandler.removeCallbacks(listenTicker)
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        DiagLog.ui(
            "windowHidden",
            "transcribing" to transcribing,
            "recording" to recorder.isRecording(),
        )
        // Some OEMs hide the IME without finishInputView — same keep policy.
        if (!transcribing && recorder.isRecording() && !endingSession) {
            saveOnlyIfKeepWorthy(notify = true)
        }
        mainHandler.removeCallbacks(listenTicker)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        DiagLog.ui(
            "startInputView",
            "restarting" to restarting,
            "package" to (info?.packageName ?: "?"),
            "inputType" to (info?.inputType ?: 0),
            "actionId" to (info?.imeOptions ?: 0),
            "recording" to recorder.isRecording(),
            "transcribing" to transcribing,
        )
        // Android often reuses the input view: after hide we already stopped the mic, so
        // we must start a *new* listen session here (not only in onCreateInputView).
        inputView?.post { ensureFreshListenSession() }
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
            // Actual mic start happens in onStartInputView via ensureFreshListenSession.
            view
        } catch (e: Exception) {
            Log.e(TAG, "onCreateInputView failed", e)
            DiagLog.e("ime", "onCreateInputView failed", e)
            fallbackView(e)
        }
    }

    /**
     * After hide/back the view may still be inflated but the mic is stopped.
     * First open: start listening. After a finished take (keep-IME): stay ready until user taps.
     */
    private fun ensureFreshListenSession() {
        if (transcribing) {
            DiagLog.i("ime", "ensure session: still processing — leave UI")
            return
        }
        if (recorder.isRecording()) {
            mainHandler.removeCallbacks(listenTicker)
            mainHandler.post(listenTicker)
            updateListenUi()
            return
        }
        if (readyForNextTake) {
            DiagLog.i("ime", "ensure session: stay ready (paused 0:00)")
            enterReadyState(showTip = false)
            return
        }
        DiagLog.i("ime", "ensure session: start fresh listen")
        startRecordingSafely()
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
        hintView = view.findViewById(R.id.hint)
        voiceCircle = view.findViewById(R.id.voice_circle)
        retryButton = view.findViewById(R.id.retry)
        doneButton = view.findViewById(R.id.done)
        newlineButton = view.findViewById(R.id.newline)
        openAppButton = view.findViewById(R.id.open_app)
        processProgress = view.findViewById(R.id.process_progress)
        showDebug = Prefs.isImeDebugOverlay(this)
        debugDetail?.visibility = if (showDebug) View.VISIBLE else View.GONE
        hideProgress()
        clearHint()

        view.findViewById<ImageButton>(R.id.switch_keyboard).setOnClickListener {
            DiagLog.ui("switch_keyboard_tap")
            cancelAndReturnToKeyboard()
        }

        // Center orb: start / pause / resume (not finish).
        view.findViewById<View>(R.id.orb_button).setOnClickListener {
            DiagLog.ui(
                "orb_tap",
                "transcribing" to transcribing,
                "ready" to readyForNextTake,
                "paused" to recorder.isPaused(),
                "recording" to recorder.isRecording(),
            )
            when {
                transcribing -> Unit
                lastFailedClip != null -> retryTranscription()
                readyForNextTake || !recorder.isRecording() -> {
                    readyForNextTake = false
                    startRecordingSafely()
                }
                recorder.isPaused() -> togglePauseResume()
                else -> togglePauseResume() // listening → pause
            }
        }

        doneButton?.setOnClickListener {
            DiagLog.ui(
                "done_tap",
                "recording" to recorder.isRecording(),
                "paused" to recorder.isPaused(),
            )
            when {
                transcribing -> Unit
                lastFailedClip != null -> retryTranscription()
                recorder.isRecording() -> finishAndTranscribe()
            }
        }

        newlineButton?.setOnClickListener {
            DiagLog.ui("newline_tap")
            insertNewline()
        }

        retryButton?.setOnClickListener {
            DiagLog.ui("retry_tap")
            retryTranscription()
        }

        openAppButton?.setOnClickListener {
            DiagLog.ui("open_settings_tap")
            openSettingsApp()
        }

        statusView?.setOnLongClickListener {
            val snap = DiagLog.statusLine()
            DiagLog.i("ime", "status long-press", "snap" to snap)
            Toast.makeText(this, snap.take(120), Toast.LENGTH_LONG).show()
            true
        }
    }

    private fun insertNewline() {
        if (transcribing) return
        val connection = currentInputConnection ?: return
        // One enter = new line; double-tap quickly still works for blank paragraph.
        connection.commitText("\n", 1)
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
        mainHandler.removeCallbacks(processTicker)
        transcribing = false
        readyForNextTake = false
        endingSession = false
        lastFailedClip = null
        lastSessionId = null
        phaseLine = ""
        pausedAccumMs = 0L
        pauseStartedAt = 0L
        hideRetry()
        showDoneButton()
        newlineButton?.visibility = View.VISIBLE
        openAppButton?.scaleX = 1f
        openAppButton?.scaleY = 1f
        hideProgress()
        progressDisplay = 0f
        voiceCircle?.resetProgress()
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.RECORDING)
        setStatus(formatTimer(0L))

        if (!XaiOauth.hasAnyAuth(this)) {
            DiagLog.w("ime", "missing auth")
            showNeedsAuth()
            return
        }

        recorder.onLevel = { rms, voice ->
            lastRms = rms
            lastVoice = voice
            if (!recorder.isPaused()) {
                voiceCircle?.setVoiceLevel(AudioLevel.normalizedLevel(rms), voice)
            }
        }

        if (!recorder.start()) {
            DiagLog.w("ime", "recorder.start failed")
            showIdleError(getString(R.string.ime_mic_error))
            return
        }
        listenStartedAt = System.currentTimeMillis()
        updateDoneHighlight(emphasized = true)
        // Keep tip visible while listening so the pause control stays obvious.
        showHint(R.string.ime_hint_listening, hideAfterMs = 0L)
        mainHandler.removeCallbacks(listenTicker)
        mainHandler.post(listenTicker)
        updateListenUi()
    }

    /** Clean fixed-size panel when user has not signed in yet. */
    private fun showNeedsAuth() {
        readyForNextTake = false
        transcribing = false
        mainHandler.removeCallbacks(listenTicker)
        mainHandler.removeCallbacks(processTicker)
        hideProgress()
        hideDoneButton()
        newlineButton?.visibility = View.INVISIBLE
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.IDLE)
        setStatus(getString(R.string.ime_auth_title))
        showHint(R.string.ime_auth_hint, hideAfterMs = 0L)
        // Make settings the obvious next step (always visible top-right).
        openAppButton?.alpha = 1f
        openAppButton?.scaleX = 1.08f
        openAppButton?.scaleY = 1.08f
    }

    /** Idle ready: 0:00, mic off — user taps orb when they want to speak. */
    private fun enterReadyState(showTip: Boolean) {
        readyForNextTake = true
        transcribing = false
        endingSession = false
        lastFailedClip = null
        lastSessionId = null
        pausedAccumMs = 0L
        pauseStartedAt = 0L
        listenStartedAt = 0L
        mainHandler.removeCallbacks(listenTicker)
        mainHandler.removeCallbacks(processTicker)
        hideProgress()
        progressDisplay = 0f
        voiceCircle?.resetProgress()
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.PAUSED)
        hideRetry()
        showDoneButton()
        newlineButton?.visibility = View.VISIBLE
        openAppButton?.scaleX = 1f
        openAppButton?.scaleY = 1f
        updateDoneHighlight(emphasized = false)
        setStatus(formatTimer(0L))
        if (showTip) {
            // Stay visible until next action — looking for the next control.
            showHint(R.string.ime_tip_ready, hideAfterMs = 0L)
        } else {
            clearHint()
        }
    }

    private fun togglePauseResume() {
        if (transcribing || !recorder.isRecording() || lastFailedClip != null) return
        if (recorder.isPaused()) {
            if (pauseStartedAt > 0L) {
                pausedAccumMs += System.currentTimeMillis() - pauseStartedAt
                pauseStartedAt = 0L
            }
            recorder.resume()
            voiceCircle?.setMode(VoiceLevelCircleView.Mode.RECORDING)
            showHint(R.string.ime_hint_listening, hideAfterMs = 0L)
            // Still recording (paused mid-take) — keep ✓ emphasized.
            updateDoneHighlight(emphasized = true)
        } else {
            recorder.pause()
            pauseStartedAt = System.currentTimeMillis()
            voiceCircle?.setMode(VoiceLevelCircleView.Mode.PAUSED)
            // Always show while paused — user is thinking and looking for ✓.
            showHint(R.string.ime_hint_tap_done, hideAfterMs = 0L)
            updateDoneHighlight(emphasized = true)
        }
        updateListenUi()
    }

    /**
     * Green outline on ✓ while a take is active (listening or paused) so “complete”
     * is distinct from the center play/pause orb.
     */
    private fun updateDoneHighlight(emphasized: Boolean) {
        val btn = doneButton ?: return
        if (emphasized) {
            btn.setBackgroundResource(R.drawable.ime_done_button_highlight)
            btn.imageTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.ime_done_icon_highlight),
                )
        } else {
            btn.setBackgroundResource(R.drawable.ime_stop_button_background)
            btn.imageTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.ime_status_text),
                )
        }
    }

    private fun activeListenMs(): Long {
        val now = System.currentTimeMillis()
        val pausedExtra = if (pauseStartedAt > 0L) now - pauseStartedAt else 0L
        return (now - listenStartedAt - pausedAccumMs - pausedExtra).coerceAtLeast(0L)
    }

    private fun formatTimer(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        val mm = sec / 60
        val ss = sec % 60
        return getString(R.string.ime_timer, mm.toInt(), ss.toInt())
    }

    private fun showTipBriefly() {
        // Listening tip is set from startRecording after mic opens.
    }

    private fun showHint(resId: Int, hideAfterMs: Long = 0L) {
        hintView?.setText(resId)
        hintView?.visibility = View.VISIBLE
        tipHideAt = if (hideAfterMs > 0L) System.currentTimeMillis() + hideAfterMs else 0L
    }

    private fun clearHint() {
        tipHideAt = 0L
        hintView?.text = ""
        // Keep layout slot (invisible, not gone).
        hintView?.visibility = View.INVISIBLE
    }

    private fun maybeHideTip() {
        // Optional timed tips only (auth / one-shots). Listening & paused tips stay up.
        if (tipHideAt > 0L && System.currentTimeMillis() >= tipHideAt) {
            tipHideAt = 0L
            if (recorder.isRecording() && recorder.isPaused()) {
                showHint(R.string.ime_hint_tap_done, hideAfterMs = 0L)
            } else if (recorder.isRecording()) {
                showHint(R.string.ime_hint_listening, hideAfterMs = 0L)
            } else if (readyForNextTake) {
                showHint(R.string.ime_tip_ready, hideAfterMs = 0L)
            } else {
                clearHint()
            }
        }
    }

    private fun updateListenUi() {
        setStatus(formatTimer(activeListenMs()))
        if (!showDebug) return
        val sec = activeListenMs() / 1000.0
        val line =
            buildString {
                append("sid=").append(DiagLog.currentSessionId())
                append("  t=").append("%.1fs".format(sec))
                append("  paused=").append(recorder.isPaused())
                append("  mic=").append(recorder.activeSourceName())
                append("  sr=").append(recorder.sampleRate())
                append('\n')
                append("rms=").append("%.3f".format(lastRms))
                append("  voice=").append(if (lastVoice) "yes" else "no")
                append("  peak=").append("%.3f".format(recorder.peakRms()))
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

    /** Gear opens the app (settings / history) — setup, not every-day path. */
    private fun openSettingsApp() {
        if (recorder.isRecording() && !transcribing) {
            saveOnlyIfKeepWorthy(notify = true)
        }
        val intent =
            Intent(this, SettingsActivity::class.java).apply {
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
        // Reset UI so a reused input view never shows a frozen clock with a dead mic.
        lastFailedClip = null
        lastSessionId = meta?.id
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.IDLE)
        hideProgress()
        hideRetry()
        showHint(R.string.ime_hint_saved_hidden, hideAfterMs = 2500L)
        if (meta != null) {
            // Keep timer visible; toast carries the save message.
            if (notify) {
                Toast.makeText(this, R.string.ime_saved_to_history, Toast.LENGTH_SHORT).show()
            }
        } else {
            setStatus(formatTimer(0L))
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
        hideDoneButton()
        clearHint()
        readyForNextTake = false
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.TRANSCRIBING)
        val uploadBytes = clip.encodedUpload?.bytes?.size ?: (clip.pcm.size / 5)
        processEstimateMs =
            ProcessingProgress.estimateTotalMs(
                uploadBytes = uploadBytes,
                speechDurationMs = clip.durationMs,
                authLikelyCached = true,
            )
        processStartedAt = System.currentTimeMillis()
        progressDisplay = 0f
        voiceCircle?.resetProgress()
        // Freeze last duration under the ring — no percentage text.
        setStatus(formatTimer(clip.durationMs))
        phaseLine = "Processing…"
        updatePhaseOverlay("Processing…")
        mainHandler.removeCallbacks(processTicker)
        mainHandler.post(processTicker)

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
                        "estimateMs" to processEstimateMs,
                    )
                    if (sessionId != null) {
                        withContext(Dispatchers.IO) { store.markOk(sessionId, text) }
                    }
                    mainHandler.removeCallbacks(processTicker)
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
                    // Trailing space so the next take doesn't glue onto the last word.
                    connection.commitText("$text ", 1)
                    val commitMs = (System.nanoTime() - commitStart) / 1_000_000L
                    DiagLog.i("ime", "commitText", "ms" to commitMs, "chars" to text.length)
                    afterSuccessfulInsert()
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
        // Product UI: frozen timer + ring only. Debug overlay can show phase.
        if (!showDebug) return
        val line =
            buildString {
                append("sid=").append(DiagLog.currentSessionId())
                append("  phase=").append(phase)
                append('\n')
                append(DiagLog.lastDetailOnly().ifBlank { DiagLog.lastStatusOnly() })
            }
        debugDetail?.text = line
    }

    private fun showTranscribeError(clip: PcmClip, message: String, sessionId: String? = lastSessionId) {
        transcribing = false
        mainHandler.removeCallbacks(processTicker)
        hideProgress()
        lastFailedClip = clip
        lastSessionId = sessionId
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.IDLE)
        showHint(R.string.ime_hint_failed, hideAfterMs = 0L)
        setStatus(message)
        if (showDebug) {
            debugDetail?.text =
                "ERROR sid=${DiagLog.currentSessionId()}\nsr=${clip.sampleRate}\n$message\n${DiagLog.lastDetailOnly()}"
        }
        showRetry()
    }

    private fun showIdleError(message: String) {
        mainHandler.removeCallbacks(listenTicker)
        mainHandler.removeCallbacks(processTicker)
        hideProgress()
        hideDoneButton()
        voiceCircle?.setMode(VoiceLevelCircleView.Mode.IDLE)
        setStatus(message)
        if (showDebug) {
            debugDetail?.text = message
        }
        hideRetry()
    }

    private fun showRetry() {
        retryButton?.visibility = View.VISIBLE
        doneButton?.visibility = View.GONE
    }

    private fun hideRetry() {
        retryButton?.visibility = View.GONE
        if (!transcribing) showDoneButton()
    }

    private fun showDoneButton() {
        doneButton?.visibility = View.VISIBLE
        updateDoneHighlight(emphasized = recorder.isRecording() && recorder.isPaused())
    }

    private fun hideDoneButton() {
        updateDoneHighlight(emphasized = false)
        doneButton?.visibility = View.GONE
    }

    private fun hideProgress() {
        processProgress?.visibility = View.GONE
        progressDisplay = 0f
        voiceCircle?.resetProgress()
    }

    /**
     * After text is inserted: either stay ready for another take (paused 0:00),
     * or return to the typing keyboard — Settings switch (default: stay).
     * Do not force progress to 100% — just leave processing UI.
     */
    private fun afterSuccessfulInsert() {
        transcribing = false
        lastFailedClip = null
        lastSessionId = null
        endingSession = false
        mainHandler.removeCallbacks(processTicker)
        // Drop processing ring as-is (even at 90%); no snap to 100%.
        if (Prefs.isKeepImeAfterStt(this)) {
            DiagLog.ui("after_insert", "keepIme" to true, "mode" to "ready")
            if (!isInputViewShown) return
            enterReadyState(showTip = true)
        } else {
            DiagLog.ui("after_insert", "keepIme" to false)
            returnToKeyboard(cancelJob = true)
        }
    }

    private fun returnToKeyboard(cancelJob: Boolean = true) {
        DiagLog.ui("returnToKeyboard", "cancelJob" to cancelJob)
        mainHandler.removeCallbacks(listenTicker)
        mainHandler.removeCallbacks(processTicker)
        hideProgress()
        if (cancelJob) {
            transcribing = false
            lastFailedClip = null
            transcribeJob?.cancel()
        }
        endingSession = false
        switchToTypingKeyboard()
    }

    /**
     * Leave voice IME for a normal typing keyboard.
     * [switchToPreviousInputMethod] alone often only hides us when the system
     * never recorded a “previous” IME — try several fallbacks.
     */
    private fun switchToTypingKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val token = window?.window?.attributes?.token

        // 1) API path on InputMethodService
        val switchedPrev =
            try {
                switchToPreviousInputMethod()
            } catch (e: Exception) {
                DiagLog.w("ime", "switchToPrevious failed", "err" to e.message)
                false
            }
        if (switchedPrev) {
            DiagLog.i("ime", "switched via switchToPreviousInputMethod")
            return
        }

        // 2) Last IME used on this token (works more often after picker switch)
        if (token != null) {
            @Suppress("DEPRECATION")
            val switchedLast =
                try {
                    imm.switchToLastInputMethod(token)
                } catch (e: Exception) {
                    DiagLog.w("ime", "switchToLastInputMethod failed", "err" to e.message)
                    false
                }
            if (switchedLast) {
                DiagLog.i("ime", "switched via switchToLastInputMethod")
                return
            }
        }

        // 3) Next IME (skip only-current) — may land on typing keyboard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && token != null) {
            val switchedNext =
                try {
                    imm.switchToNextInputMethod(token, false /* onlyCurrentIme */)
                } catch (e: Exception) {
                    DiagLog.w("ime", "switchToNext failed", "err" to e.message)
                    false
                }
            if (switchedNext) {
                DiagLog.i("ime", "switched via switchToNextInputMethod")
                return
            }
        }

        // 4) Last resort: system picker so user can pick Gboard / Samsung / etc.
        DiagLog.w("ime", "fallback showInputMethodPicker")
        try {
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            DiagLog.e("ime", "showInputMethodPicker failed", e)
            requestHideSelf(0)
        }
    }

    private fun setStatus(text: String) {
        statusView?.text = text
    }

    companion object {
        private const val TAG = "GrokVoiceIME"
    }
}
