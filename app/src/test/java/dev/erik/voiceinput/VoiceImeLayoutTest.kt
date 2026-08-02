package dev.erik.voiceinput

import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        assertNotNull(view.findViewById(R.id.orb_button))
        assertNotNull(view.findViewById(R.id.done))
        assertNotNull(view.findViewById(R.id.newline))
        assertNotNull(view.findViewById(R.id.switch_keyboard))
        assertNotNull(view.findViewById(R.id.status))
        assertNotNull(view.findViewById(R.id.open_app))
        val hint = view.findViewById<View>(R.id.hint)
        assertNotNull(hint)
        assertTrue(
            hint.visibility == View.INVISIBLE || hint.visibility == View.VISIBLE,
        )
    }

    @Test
    fun fallbackLayoutInflates() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = LayoutInflater.from(context).inflate(R.layout.voice_input_ime_fallback, null)
        assertNotNull(view.findViewById(R.id.status))
    }
}
