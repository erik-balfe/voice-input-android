package dev.erik.voiceinput

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF0B57D0),
        onPrimary = Color.White,
        background = Color(0xFFF8F9FA),
        onBackground = Color(0xFF1A1C1E),
        surface = Color(0xFFF8F9FA),
        onSurface = Color(0xFF1A1C1E),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFA8C7FA),
        onPrimary = Color(0xFF003258),
        background = Color(0xFF1A1C1E),
        onBackground = Color(0xFFE2E2E6),
        surface = Color(0xFF1A1C1E),
        onSurface = Color(0xFFE2E2E6),
    )

@Composable
fun VoiceInputTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}

/** True if the device system UI is in night mode (not the IME's possibly-stale config). */
fun isSystemNightMode(): Boolean {
    val uiMode = Resources.getSystem().configuration.uiMode
    return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

/**
 * Context whose resources follow **system** light/dark.
 * IME services often keep a light configuration; use this for inflating keyboard UI.
 */
fun Context.withSystemNightMode(): Context {
    val night =
        if (isSystemNightMode()) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
    val config = Configuration(resources.configuration)
    config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    return createConfigurationContext(config)
}
