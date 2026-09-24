package dev.erik.voiceinput

/**
 * Local voice-activity hints while listening. No STT API.
 *
 * ~8 s with no voice → a short “no voice / maybe muted” hint.
 * Several long silent takes with no successful recognition → a stronger
 * “wrong device / muted / headphones” hint. Both are dismissable.
 */
class SilenceHintTracker(
    private val weakAfterMs: Long = 8_000L,
    private val longTakeMs: Long = 8_000L,
    private val strongAfterStreak: Int = 3,
) {
    enum class Kind { NONE, WEAK, STRONG }

    var heardVoiceThisTake: Boolean = false
        private set

    private var weakDismissedThisTake = false
    private var strongDismissed = false
    private var consecutiveSilentLongTakes = 0
    private var lastKind = Kind.NONE

    fun startTake() {
        heardVoiceThisTake = false
        weakDismissedThisTake = false
        lastKind = Kind.NONE
    }

    fun onVoice() {
        heardVoiceThisTake = true
    }

    fun evaluate(
        activeListenMs: Long,
        listening: Boolean,
        paused: Boolean,
    ): Kind {
        val kind =
            when {
                consecutiveSilentLongTakes >= strongAfterStreak && !strongDismissed ->
                    Kind.STRONG
                !listening || paused -> Kind.NONE
                !heardVoiceThisTake &&
                    activeListenMs >= weakAfterMs &&
                    !weakDismissedThisTake -> Kind.WEAK
                else -> Kind.NONE
            }
        lastKind = kind
        return kind
    }

    /** Hide the current hint for this take (weak) or until the streak rebuilds (strong). */
    fun dismiss(): Boolean {
        return when (lastKind) {
            Kind.WEAK -> {
                weakDismissedThisTake = true
                lastKind = Kind.NONE
                true
            }
            Kind.STRONG -> {
                strongDismissed = true
                weakDismissedThisTake = true
                lastKind = Kind.NONE
                true
            }
            Kind.NONE -> false
        }
    }

    fun finishTake(durationMs: Long, recognizedOk: Boolean) {
        if (recognizedOk || heardVoiceThisTake) {
            consecutiveSilentLongTakes = 0
            strongDismissed = false
            return
        }
        if (durationMs >= longTakeMs) {
            consecutiveSilentLongTakes++
        }
    }
}
