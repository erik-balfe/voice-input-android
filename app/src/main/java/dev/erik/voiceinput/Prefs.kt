package dev.erik.voiceinput

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {
    private const val FILE = "voice_input_secure"
    private const val KEY_API = "xai_api_key"
    private const val KEY_LANG = "stt_language"

    private fun prefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    fun getApiKey(context: Context): String? =
        prefs(context).getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
    }

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANG, "en") ?: "en"

    fun setLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_LANG, language.trim().ifBlank { "en" }).apply()
    }
}