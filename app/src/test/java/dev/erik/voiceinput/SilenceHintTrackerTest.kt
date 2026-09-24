package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceHintTrackerTest {
    private fun tracker() = SilenceHintTracker()

    @Test
    fun noHintBeforeEightSecondsOfSilence() {
        val t = tracker()
        t.startTake()
        assertEquals(
            SilenceHintTracker.Kind.NONE,
            t.evaluate(activeListenMs = 7_999, listening = true, paused = false),
        )
    }

    @Test
    fun weakHintAfterAboutEightSecondsWithNoVoice() {
        val t = tracker()
        t.startTake()
        assertEquals(
            SilenceHintTracker.Kind.WEAK,
            t.evaluate(activeListenMs = 8_000, listening = true, paused = false),
        )
    }

    @Test
    fun voiceBeforeEightSecondsNeverShowsWeak() {
        val t = tracker()
        t.startTake()
        t.onVoice()
        assertEquals(
            SilenceHintTracker.Kind.NONE,
            t.evaluate(activeListenMs = 12_000, listening = true, paused = false),
        )
    }

    @Test
    fun dismissHidesWeakForTheRestOfTheTake() {
        val t = tracker()
        t.startTake()
        assertEquals(
            SilenceHintTracker.Kind.WEAK,
            t.evaluate(8_000, listening = true, paused = false),
        )
        assertTrue(t.dismiss())
        assertEquals(
            SilenceHintTracker.Kind.NONE,
            t.evaluate(20_000, listening = true, paused = false),
        )
    }

    @Test
    fun pausedListeningDoesNotShowWeak() {
        val t = tracker()
        t.startTake()
        assertEquals(
            SilenceHintTracker.Kind.NONE,
            t.evaluate(8_000, listening = true, paused = true),
        )
    }

    @Test
    fun voiceClearsWeakHint() {
        val t = tracker()
        t.startTake()
        t.evaluate(8_000, listening = true, paused = false)
        t.onVoice()
        assertEquals(
            SilenceHintTracker.Kind.NONE,
            t.evaluate(9_000, listening = true, paused = false),
        )
    }

    @Test
    fun twoLongSilentTakesStayWeakNotStrong() {
        val t = tracker()
        repeat(2) {
            t.startTake()
            t.finishTake(durationMs = 8_000, recognizedOk = false)
        }
        t.startTake()
        assertEquals(
            SilenceHintTracker.Kind.WEAK,
            t.evaluate(8_000, listening = true, paused = false),
        )
    }

    @Test
    fun threeLongSilentUnrecognizedTakesEscalateToStrong() {
        val t = tracker()
        repeat(3) {
            t.startTake()
            t.finishTake(durationMs = 8_000, recognizedOk = false)
        }
        t.startTake()
        assertEquals(
            SilenceHintTracker.Kind.STRONG,
            t.evaluate(0, listening = true, paused = false),
        )
    }

    @Test
    fun shortSilentTakesDoNotCountTowardStrong() {
        val t = tracker()
        repeat(5) {
            t.startTake()
            t.finishTake(durationMs = 3_000, recognizedOk = false)
        }
        t.startTake()
        assertEquals(
            SilenceHintTracker.Kind.NONE,
            t.evaluate(0, listening = true, paused = false),
        )
        assertEquals(
            SilenceHintTracker.Kind.WEAK,
            t.evaluate(8_000, listening = true, paused = false),
        )
    }

    @Test
    fun successfulRecognitionResetsStrongStreak() {
        val t = tracker()
        repeat(3) {
            t.startTake()
            t.finishTake(durationMs = 8_000, recognizedOk = false)
        }
        t.startTake()
        t.finishTake(durationMs = 8_000, recognizedOk = true)
        t.startTake()
        assertEquals(
            SilenceHintTracker.Kind.NONE,
            t.evaluate(0, listening = true, paused = false),
        )
    }

    @Test
    fun voiceDuringTakeResetsStreak() {
        val t = tracker()
        repeat(2) {
            t.startTake()
            t.finishTake(durationMs = 8_000, recognizedOk = false)
        }
        t.startTake()
        t.onVoice()
        t.finishTake(durationMs = 8_000, recognizedOk = false)
        t.startTake()
        t.finishTake(durationMs = 8_000, recognizedOk = false)
        t.startTake()
        assertEquals(
            SilenceHintTracker.Kind.WEAK,
            t.evaluate(8_000, listening = true, paused = false),
        )
    }

    @Test
    fun dismissedStrongDoesNotReturnUntilStreakRebuilds() {
        val t = tracker()
        repeat(3) {
            t.startTake()
            t.finishTake(durationMs = 8_000, recognizedOk = false)
        }
        t.startTake()
        assertEquals(SilenceHintTracker.Kind.STRONG, t.evaluate(0, true, false))
        assertTrue(t.dismiss())
        assertEquals(SilenceHintTracker.Kind.NONE, t.evaluate(8_000, true, false))
        t.finishTake(durationMs = 8_000, recognizedOk = false)
        t.startTake()
        assertEquals(
            SilenceHintTracker.Kind.NONE,
            t.evaluate(0, listening = true, paused = false),
        )
    }

    @Test
    fun dismissDoesNothingWhenNoHint() {
        val t = tracker()
        t.startTake()
        assertFalse(t.dismiss())
    }
}
