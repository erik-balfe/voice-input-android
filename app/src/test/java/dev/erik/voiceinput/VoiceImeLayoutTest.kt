package dev.erik.voiceinput

import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VoiceImeLayoutTest {
    @Test
    fun voiceInputImeLayoutInflates() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = LayoutInflater.from(context).inflate(R.layout.voice_input_ime, null)
        assertNotNull(view.findViewById<VoiceLevelCircleView>(R.id.voice_circle))
        assertNotNull(view.findViewById(R.id.stop_button))
        assertNotNull(view.findViewById(R.id.cancel))
        assertNotNull(view.findViewById(R.id.debug_detail))
        assertNotNull(view.findViewById(R.id.status))
        assertNotNull(view.findViewById(R.id.open_app))
        assertNotNull(view.findViewById(R.id.retry))
        assertNotNull(view.findViewById(R.id.pause_resume))
        assertNotNull(view.findViewById(R.id.process_progress))
        assertNotNull(view.findViewById(R.id.hint))
    }

    @Test
    fun fallbackLayoutInflates() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = LayoutInflater.from(context).inflate(R.layout.voice_input_ime_fallback, null)
        assertNotNull(view.findViewById(R.id.status))
    }
}