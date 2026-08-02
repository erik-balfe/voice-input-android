package dev.erik.voiceinput

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {
    private const val FILE = "voice_input_secure"
    private const val KEY_API = "xai_api_key"
    private const val KEY_LANG = "stt_language"
    private const val KEY_MIC_MODE = "mic_mode"
    private const val KEY_IME_DEBUG = "ime_debug_overlay"
    private const val KEY_VERBOSE = "verbose_diag"
    private const val KEY_AUTH_PREF = "auth_preference"
    private const val KEY_OAUTH_ACCESS = "oauth_access_token"
    private const val KEY_OAUTH_REFRESH = "oauth_refresh_token"
    private const val KEY_OAUTH_ID = "oauth_id_token"
    private const val KEY_OAUTH_EXPIRES = "oauth_expires_in"
    private const val KEY_OAUTH_TOKEN_EP = "oauth_token_endpoint"
    private const val KEY_OAUTH_REFRESHED_AT = "oauth_refreshed_at_unix"
    private const val KEY_OAUTH_INFO = "oauth_token_info_summary"
    private const val KEY_HISTORY_MAX_ITEMS = "history_max_items"
    private const val KEY_HISTORY_MAX_BYTES = "history_max_bytes"
    private const val KEY_KEEP_IME_AFTER_STT = "keep_ime_after_stt"

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

    fun hasApiKey(context: Context): Boolean = !getApiKey(context).isNullOrBlank()

    /** Masked preview for UI, e.g. `xai-…a1b2`. Null if none. */
    fun apiKeyPreview(context: Context): String? {
        val key = getApiKey(context) ?: return null
        return if (key.length <= 8) "••••" else "${key.take(4)}…${key.takeLast(4)}"
    }

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
        XaiOauth.clearBearerCache()
    }

    fun clearApiKey(context: Context) {
        prefs(context).edit().remove(KEY_API).apply()
        XaiOauth.clearBearerCache()
    }

    /**
     * Explicit STT credential choice. Default [AuthPreference.OAUTH] so a dead
     * API key cannot silently burn/fail after OAuth login.
     */
    fun getAuthPreference(context: Context): AuthPreference {
        val stored = prefs(context).getString(KEY_AUTH_PREF, null)
        if (stored != null) return AuthPreference.fromPref(stored)
        // Migration: if only API key exists and no OAuth, prefer key.
        return if (!isOauthStored(context) && hasApiKey(context)) {
            AuthPreference.API_KEY
        } else {
            AuthPreference.OAUTH
        }
    }

    fun setAuthPreference(context: Context, pref: AuthPreference) {
        prefs(context).edit().putString(KEY_AUTH_PREF, pref.prefValue).apply()
        XaiOauth.clearBearerCache()
    }

    private fun isOauthStored(context: Context): Boolean =
        !prefs(context).getString(KEY_OAUTH_ACCESS, null).isNullOrBlank() &&
            !prefs(context).getString(KEY_OAUTH_REFRESH, null).isNullOrBlank()

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANG, "en") ?: "en"

    fun setLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_LANG, language.trim().ifBlank { "en" }).apply()
    }

    fun getMicMode(context: Context): MicMode =
        MicMode.fromPref(prefs(context).getString(KEY_MIC_MODE, MicMode.AUTO.prefValue))

    fun setMicMode(context: Context, mode: MicMode) {
        prefs(context).edit().putString(KEY_MIC_MODE, mode.prefValue).apply()
    }

    /** Show live timing / phase detail on the voice keyboard. Default off (product UX). */
    fun isImeDebugOverlay(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IME_DEBUG, false)

    fun setImeDebugOverlay(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_IME_DEBUG, enabled).apply()
    }

    /** Extra-noisy logs (e.g. per-chunk RMS). */
    fun isVerboseDiag(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VERBOSE, false)

    fun setVerboseDiag(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VERBOSE, enabled).apply()
    }

    fun getHistoryMaxItems(context: Context): Int =
        prefs(context).getInt(KEY_HISTORY_MAX_ITEMS, RecordingStore.DEFAULT_MAX_ITEMS)
            .coerceAtLeast(1)

    fun setHistoryMaxItems(context: Context, n: Int) {
        prefs(context).edit().putInt(KEY_HISTORY_MAX_ITEMS, n.coerceAtLeast(1)).apply()
    }

    fun getHistoryMaxBytes(context: Context): Long =
        prefs(context)
            .getLong(KEY_HISTORY_MAX_BYTES, RecordingStore.DEFAULT_MAX_BYTES)
            .coerceAtLeast(1L)

    fun setHistoryMaxBytes(context: Context, bytes: Long) {
        prefs(context).edit().putLong(KEY_HISTORY_MAX_BYTES, bytes.coerceAtLeast(1L)).apply()
    }

    /**
     * When true, after successful dictation keep the voice keyboard open for another take.
     * When false (default), switch back to the previous keyboard after insert.
     */
    fun isKeepImeAfterStt(context: Context): Boolean =
        // Default on: multi-take dictation is the normal voice-keyboard loop.
        prefs(context).getBoolean(KEY_KEEP_IME_AFTER_STT, true)

    fun setKeepImeAfterStt(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_IME_AFTER_STT, enabled).apply()
    }

    // ── xAI OAuth (encrypted) ─────────────────────────────────

    fun getOauthAccessToken(context: Context): String? =
        prefs(context).getString(KEY_OAUTH_ACCESS, null)?.takeIf { it.isNotBlank() }

    fun getOauthRefreshToken(context: Context): String? =
        prefs(context).getString(KEY_OAUTH_REFRESH, null)?.takeIf { it.isNotBlank() }

    fun getOauthTokenEndpoint(context: Context): String =
        prefs(context).getString(KEY_OAUTH_TOKEN_EP, "") ?: ""

    fun setOauthTokens(
        context: Context,
        access: String,
        refresh: String,
        idToken: String = "",
        expiresIn: Long? = null,
        tokenEndpoint: String = "",
    ) {
        prefs(context).edit()
            .putString(KEY_OAUTH_ACCESS, access)
            .putString(KEY_OAUTH_REFRESH, refresh)
            .putString(KEY_OAUTH_ID, idToken)
            .putLong(KEY_OAUTH_EXPIRES, expiresIn ?: 0L)
            .putString(KEY_OAUTH_TOKEN_EP, tokenEndpoint)
            .putLong(KEY_OAUTH_REFRESHED_AT, System.currentTimeMillis() / 1000)
            // Successful login → STT uses OAuth (do not keep a dead API key selected).
            .putString(KEY_AUTH_PREF, AuthPreference.OAUTH.prefValue)
            .apply()
    }

    fun setOauthTokenInfoSummary(context: Context, summary: String) {
        prefs(context).edit().putString(KEY_OAUTH_INFO, summary).apply()
    }

    fun getOauthTokenInfoSummary(context: Context): String =
        prefs(context).getString(KEY_OAUTH_INFO, "") ?: ""

    fun clearOauthTokenInfo(context: Context) {
        prefs(context).edit().remove(KEY_OAUTH_INFO).apply()
    }

    fun clearOauth(context: Context) {
        prefs(context).edit()
            .remove(KEY_OAUTH_ACCESS)
            .remove(KEY_OAUTH_REFRESH)
            .remove(KEY_OAUTH_ID)
            .remove(KEY_OAUTH_EXPIRES)
            .remove(KEY_OAUTH_TOKEN_EP)
            .remove(KEY_OAUTH_REFRESHED_AT)
            .remove(KEY_OAUTH_INFO)
            .apply()
    }
}
