package dev.erik.voiceinput

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiagLogTest {
    @Test
    fun writesAndReadsLog() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DiagLog.clear(context)
        DiagLog.beginSession("test")
        DiagLog.i("unit", "hello", "n" to 1)
        val tail = DiagLog.readTail(context)
        assertTrue("expected hello in: $tail", tail.contains("hello"))
        assertTrue("expected [unit] in: $tail", tail.contains("[unit]"))
        assertTrue(DiagLog.logFile(context).exists())
    }

    @Test
    fun timingRecordsMs() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DiagLog.init(context)
        val t = DiagLog.start("unit", "phase")
        val ms = t.end("ok" to true)
        assertTrue(ms >= 0)
        assertTrue(DiagLog.readTail(context).contains("phase end"))
    }
}
