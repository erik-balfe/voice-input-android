package dev.erik.voiceinput

import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * xAI Grok OAuth (SuperGrok / X Premium+) — device-code login.
 *
 * Same public Grok CLI client as cosmic-scribe / Hermes:
 * sign in once against auth.x.ai, then STT uses subscription quota
 * instead of pay-per-token API keys.
 *
 * Own session only — tokens live in this app's encrypted prefs.
 */
object XaiOauth {
    private const val DISCOVERY_URL = "https://auth.x.ai/.well-known/openid-configuration"
    private const val DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code"
    /** Hardcoded fallback if OIDC discovery DNS fails. */
    private const val TOKEN_URL_FALLBACK = "https://auth.x.ai/oauth2/token"
    private const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    private const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
    /** Refresh this many seconds before JWT exp (~6h tokens). */
    private const val REFRESH_SKEW_SECS = 3600L

    private val http =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    private val refreshLock = Any()
    private val loginCancel = AtomicBoolean(false)

    /**
     * In-memory bearer so we don’t hit EncryptedSharedPreferences + JWT parse
     * on every dictation (~0.5–1.5 s on some devices).
     */
    private data class CachedBearer(
        val token: String,
        /** Unix seconds; for API keys use far future. */
        val usableUntilUnix: Long,
        val mode: AuthPreference,
    )

    @Volatile
    private var bearerCache: CachedBearer? = null

    fun clearBearerCache() {
        bearerCache = null
    }

    data class DeviceStart(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSec: Long,
        val intervalSec: Long,
        val tokenEndpoint: String,
    )

    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String = "",
        val expiresIn: Long? = null,
        val tokenType: String = "Bearer",
        val tokenEndpoint: String = "",
    )

    /** Non-secret JWT fields for debugging (compare with cosmic-scribe). */
    data class TokenInfo(
        val sub: String = "",
        val teamId: String = "",
        val scope: String = "",
        val aud: String = "",
        val clientId: String = "",
        val iss: String = "",
        val tier: String = "",
        val exp: Long = 0,
        val principalType: String = "",
    ) {
        fun summaryLine(): String =
            buildString {
                append("team=").append(teamId.ifBlank { "?" }.take(8)).append("…")
                append(" sub=").append(sub.ifBlank { "?" }.take(8)).append("…")
                if (tier.isNotBlank()) append(" tier=").append(tier)
                if (scope.isNotBlank()) {
                    append(" scope=").append(scope.take(60))
                    if (scope.length > 60) append("…")
                }
            }

        fun detailBlock(): String =
            """
            sub=$sub
            team_id=$teamId
            tier=$tier
            scope=$scope
            aud=$aud
            client_id=$clientId
            iss=$iss
            principal_type=$principalType
            exp=$exp
            """.trimIndent()
    }

    fun isLoggedIn(context: Context): Boolean =
        !Prefs.getOauthAccessToken(context).isNullOrBlank()

    fun looksLikeOauthBearer(token: String): Boolean =
        token.isNotEmpty() && !token.startsWith("xai-") && token.count { it == '.' } == 2

    fun cancelLogin() {
        loginCancel.set(true)
    }

    fun logout(context: Context) {
        Prefs.clearOauth(context)
        Prefs.clearOauthTokenInfo(context)
        clearBearerCache()
        DiagLog.i("oauth", "logout")
    }

    fun tokenInfo(context: Context): TokenInfo? {
        val access = Prefs.getOauthAccessToken(context) ?: return null
        return parseTokenInfo(access)
    }

    fun parseTokenInfo(accessToken: String): TokenInfo? {
        if (!looksLikeOauthBearer(accessToken)) return null
        val parts = accessToken.split('.')
        if (parts.size != 3) return null
        return try {
            val payload = base64UrlDecode(parts[1]) ?: return null
            val json = JSONObject(String(payload, Charsets.UTF_8))
            TokenInfo(
                sub = json.optString("sub", ""),
                teamId = json.optString("team_id", ""),
                scope = json.optString("scope", ""),
                aud = json.optString("aud", ""),
                clientId = json.optString("client_id", ""),
                iss = json.optString("iss", ""),
                tier = json.optString("tier", ""),
                exp = json.optLong("exp", 0),
                principalType = json.optString("principal_type", ""),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun logTokenInfo(tag: String, accessToken: String) {
        val info = parseTokenInfo(accessToken)
        if (info == null) {
            DiagLog.w(
                "oauth",
                "$tag token_info missing",
                "looksJwt" to looksLikeOauthBearer(accessToken),
                "prefix" to accessToken.take(12),
            )
            return
        }
        DiagLog.i(
            "oauth",
            "$tag token_info",
            "sub" to info.sub,
            "team_id" to info.teamId,
            "tier" to info.tier,
            "scope" to info.scope,
            "aud" to info.aud,
            "client_id" to info.clientId,
            "iss" to info.iss,
            "principal_type" to info.principalType,
            "exp" to info.exp,
            "has_api_access" to info.scope.contains("api:access"),
            "has_grok_cli" to info.scope.contains("grok-cli:access"),
        )
    }

    /**
     * One-shot STT probe with a short tone WAV — same endpoint/form as real dictation.
     * Returns human-readable result for Settings (HTTP code + body snippet).
     */
    fun probeStt(context: Context): String {
        val bearer = resolveBearer(context)
        logTokenInfo("probe", bearer)
        val wav = syntheticProbeWav()
        val fileBody = wav.toRequestBody("audio/wav".toMediaType())
        val multipart =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("format", "true")
                .addFormDataPart("language", Prefs.getLanguage(context))
                .addFormDataPart("file", "recording.wav", fileBody)
                .build()
        val request =
            Request.Builder()
                .url("https://api.x.ai/v1/stt")
                .header("Authorization", "Bearer $bearer")
                .post(multipart)
                .build()
        val t0 = System.nanoTime()
        http.newCall(request).execute().use { resp ->
            val ms = (System.nanoTime() - t0) / 1_000_000L
            val body = resp.body?.string().orEmpty()
            DiagLog.i(
                "oauth",
                "probe_stt",
                "code" to resp.code,
                "ms" to ms,
                "body" to body.take(300),
                "pref" to Prefs.getAuthPreference(context).prefValue,
            )
            return "HTTP ${resp.code} (${ms}ms)\n${body.take(280)}"
        }
    }

    private fun syntheticProbeWav(): ByteArray {
        val sampleRate = 16_000
        val samples = (sampleRate * 0.6).toInt()
        val pcm = ByteArray(samples * 2)
        var i = 0
        while (i < samples) {
            val s = if (i % 2 == 0) 200 else -200
            pcm[i * 2] = (s and 0xFF).toByte()
            pcm[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            i++
        }
        return WavEncoder.encodePcm16Mono(pcm, sampleRate)
    }

    /**
     * Resolve a Bearer for STT from the **selected** [AuthPreference].
     * No silent fallback between OAuth and API key — avoids a zero-balance key
     * stealing traffic after a successful SuperGrok login.
     *
     * Uses an in-memory cache so dictation doesn’t re-read encrypted storage every time.
     */
    fun resolveBearer(context: Context): String {
        val pref = Prefs.getAuthPreference(context)
        val now = System.currentTimeMillis() / 1000L
        bearerCache?.let { c ->
            if (c.mode == pref && now + 30 < c.usableUntilUnix) {
                DiagLog.i(
                    "oauth",
                    "resolveBearer cache hit",
                    "pref" to pref.prefValue,
                    "ttlSec" to (c.usableUntilUnix - now),
                )
                return c.token
            }
        }

        DiagLog.i(
            "oauth",
            "resolveBearer",
            "pref" to pref.prefValue,
            "oauthLoggedIn" to isLoggedIn(context),
            "hasApiKey" to Prefs.hasApiKey(context),
            "cache" to "miss",
        )
        val token =
            when (pref) {
                AuthPreference.OAUTH -> {
                    if (!isLoggedIn(context)) {
                        throw SttException(
                            "STT set to OAuth — sign in with xAI in Settings (or switch to API key)",
                        )
                    }
                    try {
                        val tok = accessToken(context)
                        logTokenInfo("resolve", tok)
                        tok
                    } catch (e: SttException) {
                        throw e
                    } catch (e: Exception) {
                        DiagLog.e("oauth", "access token failed", e)
                        throw SttException(
                            "OAuth session expired — sign in again in Settings",
                            e,
                        )
                    }
                }
                AuthPreference.API_KEY -> {
                    Prefs.getApiKey(context)
                        ?: throw SttException(
                            "STT set to API key — paste a key in Settings (or switch to OAuth)",
                        )
                }
            }

        val usableUntil =
            when (pref) {
                AuthPreference.OAUTH -> {
                    // Cache until ~2 min before refresh skew would kick in, or JWT exp - 120s.
                    val exp = jwtExp(token) ?: (now + 3600)
                    minOf(exp - 120, now + 3600)
                }
                AuthPreference.API_KEY -> now + 86400 * 30
            }
        bearerCache = CachedBearer(token, usableUntil, pref)
        return token
    }

    fun hasAnyAuth(context: Context): Boolean =
        when (Prefs.getAuthPreference(context)) {
            AuthPreference.OAUTH -> isLoggedIn(context)
            AuthPreference.API_KEY -> Prefs.hasApiKey(context)
        }

    /** Short user-facing status (no team IDs / debug claims). */
    fun activeCredentialLabel(context: Context): String {
        val pref = Prefs.getAuthPreference(context)
        return when (pref) {
            AuthPreference.OAUTH ->
                if (isLoggedIn(context)) {
                    "Signed in with xAI"
                } else {
                    "Sign in with xAI to use subscription"
                }
            AuthPreference.API_KEY ->
                if (Prefs.hasApiKey(context)) {
                    "API key saved (${Prefs.apiKeyPreview(context)})"
                } else {
                    "Add an API key"
                }
        }
    }

    fun authModeLabel(context: Context): String =
        when (Prefs.getAuthPreference(context)) {
            AuthPreference.OAUTH -> if (isLoggedIn(context)) "oauth" else "oauth_missing"
            AuthPreference.API_KEY -> if (Prefs.hasApiKey(context)) "api_key" else "api_key_missing"
        }

    /** Blocking: usable access token, refresh if near expiry. */
    fun accessToken(context: Context): String {
        val access = Prefs.getOauthAccessToken(context)
            ?: throw IOException("no xAI OAuth session")
        if (!accessTokenNeedsRefresh(access)) {
            return access
        }
        val refresh = Prefs.getOauthRefreshToken(context)
        if (refresh.isNullOrBlank()) {
            // Still within JWT exp? accessTokenNeedsRefresh uses 1h skew — if truly expired, fail.
            val exp = jwtExp(access) ?: throw IOException("OAuth access token expired (no refresh)")
            if (System.currentTimeMillis() / 1000 < exp) {
                DiagLog.w("oauth", "using access token inside exp without refresh token")
                return access
            }
            throw IOException("OAuth access token expired — sign in again (no refresh token)")
        }
        DiagLog.i("oauth", "access token near expiry; refreshing")
        return forceRefresh(context)
    }

    /**
     * Import OAuth session exported from cosmic-scribe (or any same-client tokens).
     * JSON keys: access_token, refresh_token (optional), token_endpoint (optional).
     */
    fun importSessionJson(context: Context, jsonText: String): TokenInfo {
        val json = JSONObject(jsonText.trim())
        val access = json.optString("access_token", "").ifBlank {
            json.optString("accessToken", "")
        }
        val refresh = json.optString("refresh_token", "").ifBlank {
            json.optString("refreshToken", "")
        }
        val endpoint = json.optString("token_endpoint", "").ifBlank {
            json.optString("tokenEndpoint", TOKEN_URL_FALLBACK)
        }
        if (access.isBlank()) {
            throw IOException("import JSON missing access_token")
        }
        if (!looksLikeOauthBearer(access)) {
            throw IOException("access_token does not look like an OAuth JWT")
        }
        if (endpoint.isNotBlank()) {
            validateXaiUrl(endpoint, "token_endpoint")
        }
        Prefs.setOauthTokens(
            context,
            access = access,
            refresh = refresh, // may be blank; STT works until access expires
            tokenEndpoint = endpoint.ifBlank { TOKEN_URL_FALLBACK },
        )
        clearBearerCache()
        logTokenInfo("import", access)
        val info = parseTokenInfo(access)
        Prefs.setOauthTokenInfoSummary(context, info?.summaryLine() ?: "")
        DiagLog.i(
            "oauth",
            "import ok",
            "hasRefresh" to refresh.isNotBlank(),
            "team_id" to (info?.teamId ?: "?"),
            "sub" to (info?.sub ?: "?"),
        )
        return info ?: TokenInfo()
    }

    /** Blocking force refresh (e.g. after HTTP 401). */
    fun forceRefresh(context: Context): String {
        synchronized(refreshLock) {
            val access = Prefs.getOauthAccessToken(context)
            val refresh =
                Prefs.getOauthRefreshToken(context)
                    ?: throw IOException("no xAI OAuth session")
            // Another thread may have refreshed already.
            if (access != null && !accessTokenNeedsRefresh(access)) {
                return access
            }
            val endpoint =
                Prefs.getOauthTokenEndpoint(context).takeIf { it.isNotBlank() }
                    ?: discoveryTokenEndpoint()
            validateXaiUrl(endpoint, "token_endpoint")

            val body =
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", CLIENT_ID)
                    .add("refresh_token", refresh)
                    .build()
            val request =
                Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/json")
                    .post(body)
                    .build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 403) {
                    throw IOException(
                        "OAuth refresh 403 — account may lack API access via OAuth. Use API key. $text",
                    )
                }
                if (!resp.isSuccessful) {
                    if (text.contains("invalid_grant")) {
                        Prefs.clearOauth(context)
                        throw IOException("OAuth session expired — sign in again")
                    }
                    throw IOException("OAuth refresh HTTP ${resp.code}: $text")
                }
                val json = JSONObject(text)
                val newAccess = json.optString("access_token", "")
                val newRefresh =
                    json.optString("refresh_token", "").ifBlank { refresh }
                if (newAccess.isBlank()) {
                    throw IOException("refresh response missing access_token")
                }
                Prefs.setOauthTokens(
                    context,
                    access = newAccess,
                    refresh = newRefresh,
                    idToken = json.optString("id_token", ""),
                    expiresIn = json.optLong("expires_in").takeIf { it > 0 },
                    tokenEndpoint = endpoint,
                )
                clearBearerCache()
                logTokenInfo("refresh", newAccess)
                Prefs.setOauthTokenInfoSummary(context, parseTokenInfo(newAccess)?.summaryLine() ?: "")
                DiagLog.i("oauth", "refresh ok", "prefix" to newAccess.take(8))
                // Warm cache immediately.
                val now = System.currentTimeMillis() / 1000L
                val exp = jwtExp(newAccess) ?: (now + 3600)
                bearerCache =
                    CachedBearer(
                        newAccess,
                        minOf(exp - 120, now + 3600),
                        AuthPreference.OAUTH,
                    )
                return newAccess
            }
        }
    }

    /** Start device-code flow (blocking). Open [DeviceStart.verificationUri] in a browser. */
    fun startDeviceCode(): DeviceStart {
        val tokenEndpoint =
            try {
                discoveryTokenEndpoint()
            } catch (e: Exception) {
                DiagLog.w("oauth", "discovery failed; using token URL fallback", "err" to e.message)
                TOKEN_URL_FALLBACK
            }
        val body =
            FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("scope", SCOPE)
                .build()
        val request =
            Request.Builder()
                .url(DEVICE_CODE_URL)
                .header("Accept", "application/json")
                .post(body)
                .build()
        executeWithDnsRetry("device_code") {
            http.newCall(request).execute()
        }.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("device-code request failed: $text")
            }
            val json = JSONObject(text)
            val deviceCode = json.optString("device_code", "")
            if (deviceCode.isBlank()) throw IOException("missing device_code")
            val userCode = json.optString("user_code", "?")
            val verification =
                json.optString("verification_uri_complete", "")
                    .ifBlank { json.optString("verification_uri", "") }
                    .ifBlank { "https://accounts.x.ai/oauth2/device" }
            val expiresIn = json.optLong("expires_in", 1800).coerceAtLeast(60)
            val interval = json.optLong("interval", 5).coerceAtLeast(1)
            DiagLog.i(
                "oauth",
                "device code started",
                "userCode" to userCode,
                "expiresIn" to expiresIn,
                "verificationHost" to (Uri.parse(verification).host ?: "?"),
                "verificationUri" to verification.take(120),
                "client_id" to CLIENT_ID,
                "scope" to SCOPE,
            )
            return DeviceStart(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verification,
                expiresInSec = expiresIn,
                intervalSec = interval,
                tokenEndpoint = tokenEndpoint,
            )
        }
    }

    /**
     * Poll until the user approves in the browser (blocking).
     * Throws on timeout / error. Respects [cancelLogin].
     */
    fun pollDeviceToken(context: Context, start: DeviceStart): Tokens {
        loginCancel.set(false)
        var interval = start.intervalSec
        val deadline = System.currentTimeMillis() + start.expiresInSec * 1000
        validateXaiUrl(start.tokenEndpoint, "token_endpoint")

        while (System.currentTimeMillis() < deadline) {
            if (loginCancel.get()) {
                throw IOException("login cancelled")
            }
            val body =
                FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("client_id", CLIENT_ID)
                    .add("device_code", start.deviceCode)
                    .build()
            val request =
                Request.Builder()
                    .url(start.tokenEndpoint)
                    .header("Accept", "application/json")
                    .post(body)
                    .build()
            val resp =
                try {
                    executeWithDnsRetry("token_poll") {
                        http.newCall(request).execute()
                    }
                } catch (e: Exception) {
                    DiagLog.w("oauth", "poll transport error; sleep and continue", "err" to e.message)
                    Thread.sleep(interval * 1000)
                    continue
                }
            resp.use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string().orEmpty())
                    val access = json.optString("access_token", "")
                    val refresh = json.optString("refresh_token", "")
                    if (access.isBlank()) {
                        throw IOException("token response missing access_token")
                    }
                    // refresh may be empty on some IdPs — still usable until access exp
                    val tokens =
                        Tokens(
                            accessToken = access,
                            refreshToken = refresh,
                            idToken = json.optString("id_token", ""),
                            expiresIn = json.optLong("expires_in").takeIf { it > 0 },
                            tokenType = json.optString("token_type", "Bearer"),
                            tokenEndpoint = start.tokenEndpoint,
                        )
                    Prefs.setOauthTokens(
                        context,
                        access = tokens.accessToken,
                        refresh = tokens.refreshToken,
                        idToken = tokens.idToken,
                        expiresIn = tokens.expiresIn,
                        tokenEndpoint = tokens.tokenEndpoint,
                    )
                    clearBearerCache()
                    logTokenInfo("login", tokens.accessToken)
                    Prefs.setOauthTokenInfoSummary(
                        context,
                        parseTokenInfo(tokens.accessToken)?.summaryLine() ?: "",
                    )
                    DiagLog.i("oauth", "login ok")
                    return tokens
                }
                val text = response.body?.string().orEmpty()
                val err =
                    try {
                        JSONObject(text).optString("error", "")
                    } catch (_: Exception) {
                        ""
                    }
                when (err) {
                    "authorization_pending" -> {
                        Thread.sleep(interval * 1000)
                    }
                    "slow_down" -> {
                        interval = (interval + 1).coerceAtMost(30)
                        Thread.sleep(interval * 1000)
                    }
                    else -> {
                        val desc =
                            try {
                                JSONObject(text).optString("error_description", text)
                            } catch (_: Exception) {
                                text
                            }
                        throw IOException("device authorization failed: $desc")
                    }
                }
            }
        }
        throw IOException("Timed out waiting for xAI authorization")
    }

    /** Retry OkHttp calls that fail on flaky mobile DNS (UnknownHost). */
    private fun executeWithDnsRetry(
        label: String,
        attempts: Int = 6,
        block: () -> okhttp3.Response,
    ): okhttp3.Response {
        var last: Exception? = null
        for (i in 0 until attempts) {
            try {
                return block()
            } catch (e: java.net.UnknownHostException) {
                last = e
                DiagLog.w("oauth", "dns fail", "label" to label, "attempt" to i, "err" to e.message)
                Thread.sleep(400L * (i + 1))
            } catch (e: IOException) {
                val msg = e.message.orEmpty()
                if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true)
                ) {
                    last = e
                    DiagLog.w("oauth", "dns fail", "label" to label, "attempt" to i, "err" to msg)
                    Thread.sleep(400L * (i + 1))
                } else {
                    throw e
                }
            }
        }
        throw IOException(
            "DNS failed for $label after $attempts tries: ${last?.message}. " +
                "Check Private DNS / VPN, or Import OAuth JSON from cosmic-scribe.",
            last,
        )
    }

    fun openVerificationInBrowser(context: Context, uri: String) {
        try {
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
            DiagLog.i("oauth", "opened browser", "uri" to uri.take(80))
        } catch (e: Exception) {
            DiagLog.w("oauth", "browser open failed", "err" to e.message)
        }
    }

    fun accessTokenNeedsRefresh(accessToken: String): Boolean {
        val exp = jwtExp(accessToken) ?: return false
        val now = System.currentTimeMillis() / 1000
        return now + REFRESH_SKEW_SECS >= exp
    }

    /** Decode JWT `exp` claim; non-JWT returns null. */
    fun jwtExp(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        return try {
            val payload = base64UrlDecode(parts[1]) ?: return null
            val json = JSONObject(String(payload, Charsets.UTF_8))
            if (!json.has("exp")) null else json.getLong("exp")
        } catch (_: Exception) {
            null
        }
    }

    private fun discoveryTokenEndpoint(): String {
        val request =
            Request.Builder()
                .url(DISCOVERY_URL)
                .header("Accept", "application/json")
                .get()
                .build()
        executeWithDnsRetry("discovery") {
            http.newCall(request).execute()
        }.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("OIDC discovery HTTP ${resp.code}")
            }
            val json = JSONObject(text)
            val tokenEp = json.optString("token_endpoint", "")
            if (tokenEp.isBlank()) throw IOException("discovery missing token_endpoint")
            validateXaiUrl(tokenEp, "token_endpoint")
            return tokenEp
        }
    }

    private fun validateXaiUrl(url: String, field: String) {
        val u = url.trim()
        if (u.isEmpty()) throw IOException("$field is empty")
        val uri = Uri.parse(u)
        if (uri.scheme != "https") throw IOException("$field must be https")
        val host = uri.host ?: ""
        if (host != "auth.x.ai" && !host.endsWith(".x.ai")) {
            throw IOException("$field host must be auth.x.ai (got $host)")
        }
    }

    private fun base64UrlDecode(s: String): ByteArray? {
        return try {
            var std = s.replace('-', '+').replace('_', '/')
            while (std.length % 4 != 0) std += "="
            android.util.Base64.decode(std, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }
}
