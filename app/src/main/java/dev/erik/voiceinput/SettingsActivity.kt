package dev.erik.voiceinput

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class SettingsActivity : ComponentActivity() {
    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        DiagLog.i(
            "settings",
            "onCreate",
            "auth" to XaiOauth.authModeLabel(this),
            "pref" to Prefs.getAuthPreference(this).prefValue,
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            var authPref by remember { mutableStateOf(Prefs.getAuthPreference(this)) }
            var oauthLoggedIn by remember { mutableStateOf(XaiOauth.isLoggedIn(this)) }
            var hasApiKey by remember { mutableStateOf(Prefs.hasApiKey(this)) }
            var apiKeyPreview by remember { mutableStateOf(Prefs.apiKeyPreview(this)) }
            var apiKeyDraft by remember { mutableStateOf("") }
            var language by remember { mutableStateOf(Prefs.getLanguage(this)) }
            var micMode by remember { mutableStateOf(Prefs.getMicMode(this)) }
            var imeDebug by remember { mutableStateOf(Prefs.isImeDebugOverlay(this)) }
            var verbose by remember { mutableStateOf(Prefs.isVerboseDiag(this)) }
            var loginStatus by remember { mutableStateOf("") }
            var loginInProgress by remember { mutableStateOf(false) }
            var tokenDetail by remember {
                mutableStateOf(
                    XaiOauth.tokenInfo(this)?.detailBlock()
                        ?: Prefs.getOauthTokenInfoSummary(this).ifBlank { "" },
                )
            }
            var probeResult by remember { mutableStateOf("") }
            var probeRunning by remember { mutableStateOf(false) }
            var importJson by remember { mutableStateOf("") }

            fun refreshAuthUi() {
                oauthLoggedIn = XaiOauth.isLoggedIn(this@SettingsActivity)
                hasApiKey = Prefs.hasApiKey(this@SettingsActivity)
                apiKeyPreview = Prefs.apiKeyPreview(this@SettingsActivity)
                authPref = Prefs.getAuthPreference(this@SettingsActivity)
                tokenDetail =
                    XaiOauth.tokenInfo(this@SettingsActivity)?.detailBlock()
                        ?: Prefs.getOauthTokenInfoSummary(this@SettingsActivity)
            }

            fun activeLine(): String = XaiOauth.activeCredentialLabel(this@SettingsActivity)

            VoiceInputTheme {
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(20.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                        )

                        Button(
                            onClick = {
                                startActivity(
                                    Intent(this@SettingsActivity, HistoryActivity::class.java),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.history_open))
                        }

                        // ── Active method ─────────────────────────────
                        Text(
                            text = stringResource(R.string.auth_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.auth_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = activeLine(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        Text(
                            text = stringResource(R.string.auth_choose_title),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        AuthPreference.entries.forEach { mode ->
                            val enabled =
                                when (mode) {
                                    AuthPreference.OAUTH -> true
                                    AuthPreference.API_KEY -> true
                                }
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = authPref == mode,
                                            enabled = enabled,
                                            onClick = {
                                                authPref = mode
                                                Prefs.setAuthPreference(
                                                    this@SettingsActivity,
                                                    mode,
                                                )
                                                DiagLog.i(
                                                    "settings",
                                                    "auth pref",
                                                    "pref" to mode.prefValue,
                                                )
                                                scope.launch {
                                                    snackbar.showSnackbar(
                                                        getString(
                                                            R.string.auth_pref_saved,
                                                            mode.label,
                                                        ),
                                                    )
                                                }
                                            },
                                            role = Role.RadioButton,
                                        )
                                        .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = authPref == mode,
                                    onClick = {
                                        authPref = mode
                                        Prefs.setAuthPreference(this@SettingsActivity, mode)
                                    },
                                )
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(text = mode.label)
                                    if (mode == AuthPreference.OAUTH) {
                                        Text(
                                            text =
                                                if (oauthLoggedIn) {
                                                    getString(R.string.oauth_status_in)
                                                } else {
                                                    getString(R.string.oauth_status_out)
                                                },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    } else {
                                        Text(
                                            text =
                                                if (hasApiKey) {
                                                    getString(
                                                        R.string.api_key_status_present,
                                                        apiKeyPreview ?: "••••",
                                                    )
                                                } else {
                                                    getString(R.string.api_key_status_none)
                                                },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }

                        if (loginStatus.isNotBlank()) {
                            Text(
                                text = loginStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        // ── OAuth session ─────────────────────────────
                        Text(
                            text = stringResource(R.string.oauth_section),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text =
                                if (oauthLoggedIn) {
                                    getString(R.string.oauth_status_in)
                                } else {
                                    getString(R.string.oauth_status_out)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!oauthLoggedIn) {
                            Button(
                                enabled = !loginInProgress,
                                onClick = {
                                    if (loginInProgress) {
                                        scope.launch {
                                            snackbar.showSnackbar(getString(R.string.oauth_busy))
                                        }
                                        return@Button
                                    }
                                    loginInProgress = true
                                    loginStatus = getString(R.string.oauth_busy)
                                    loginJob?.cancel()
                                    loginJob =
                                        scope.launch {
                                            try {
                                                val start =
                                                    withContext(Dispatchers.IO) {
                                                        XaiOauth.startDeviceCode()
                                                    }
                                                loginStatus =
                                                    getString(
                                                        R.string.oauth_waiting,
                                                        start.userCode,
                                                    )
                                                XaiOauth.openVerificationInBrowser(
                                                    this@SettingsActivity,
                                                    start.verificationUri,
                                                )
                                                snackbar.showSnackbar(
                                                    getString(R.string.oauth_open_browser),
                                                )
                                                withContext(Dispatchers.IO) {
                                                    XaiOauth.pollDeviceToken(
                                                        this@SettingsActivity,
                                                        start,
                                                    )
                                                }
                                                // setOauthTokens already selects OAuth pref
                                                refreshAuthUi()
                                                loginStatus = getString(R.string.oauth_success)
                                                snackbar.showSnackbar(
                                                    getString(R.string.oauth_success),
                                                )
                                            } catch (e: Exception) {
                                                DiagLog.e("settings", "oauth login failed", e)
                                                loginStatus =
                                                    getString(
                                                        R.string.oauth_failed,
                                                        e.message ?: "error",
                                                    )
                                                snackbar.showSnackbar(loginStatus)
                                            } finally {
                                                loginInProgress = false
                                            }
                                        }
                                },
                            ) {
                                Text(stringResource(R.string.oauth_sign_in))
                            }
                            if (loginInProgress) {
                                TextButton(
                                    onClick = {
                                        XaiOauth.cancelLogin()
                                        loginJob?.cancel()
                                        loginInProgress = false
                                        loginStatus = ""
                                    },
                                ) {
                                    Text(stringResource(R.string.oauth_cancel))
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    XaiOauth.logout(this@SettingsActivity)
                                    refreshAuthUi()
                                    loginStatus = ""
                                    tokenDetail = ""
                                    probeResult = ""
                                    scope.launch {
                                        snackbar.showSnackbar(getString(R.string.oauth_status_out))
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.oauth_sign_out))
                            }
                        }

                        Text(
                            text = stringResource(R.string.oauth_token_info_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text =
                                tokenDetail.ifBlank {
                                    getString(R.string.oauth_token_info_none)
                                },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                        Text(
                            text = stringResource(R.string.oauth_same_account_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            enabled = !probeRunning && XaiOauth.hasAnyAuth(this@SettingsActivity),
                            onClick = {
                                probeRunning = true
                                probeResult = getString(R.string.oauth_probe_running)
                                scope.launch {
                                    try {
                                        val result =
                                            withContext(Dispatchers.IO) {
                                                XaiOauth.probeStt(this@SettingsActivity)
                                            }
                                        probeResult = result
                                        refreshAuthUi()
                                        snackbar.showSnackbar(result.lines().firstOrNull() ?: result)
                                    } catch (e: Exception) {
                                        DiagLog.e("settings", "probe failed", e)
                                        probeResult = e.message ?: "probe failed"
                                        snackbar.showSnackbar(probeResult)
                                    } finally {
                                        probeRunning = false
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.oauth_probe_stt))
                        }
                        if (probeResult.isNotBlank()) {
                            Text(
                                text = probeResult,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                        }

                        Text(
                            text = stringResource(R.string.oauth_import_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.oauth_import_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = importJson,
                            onValueChange = { importJson = it },
                            label = { Text(stringResource(R.string.oauth_import_hint)) },
                            minLines = 3,
                            maxLines = 8,
                        )
                        Button(
                            enabled = importJson.isNotBlank(),
                            onClick = {
                                try {
                                    val info =
                                        XaiOauth.importSessionJson(
                                            this@SettingsActivity,
                                            importJson,
                                        )
                                    importJson = ""
                                    refreshAuthUi()
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            getString(
                                                R.string.oauth_import_ok,
                                                info.teamId.take(8).ifBlank { "?" },
                                            ),
                                        )
                                    }
                                } catch (e: Exception) {
                                    DiagLog.e("settings", "import failed", e)
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            getString(
                                                R.string.oauth_import_fail,
                                                e.message ?: "error",
                                            ),
                                        )
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.oauth_import_apply))
                        }

                        // ── API key ───────────────────────────────────
                        Text(
                            text = stringResource(R.string.api_key_section),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text =
                                if (hasApiKey) {
                                    getString(
                                        R.string.api_key_status_present,
                                        apiKeyPreview ?: "••••",
                                    )
                                } else {
                                    getString(R.string.api_key_status_none)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (hasApiKey && authPref == AuthPreference.OAUTH) {
                            Text(
                                text = stringResource(R.string.api_key_warn_zero),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = apiKeyDraft,
                            onValueChange = { apiKeyDraft = it },
                            label = { Text(stringResource(R.string.api_key_hint)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = {
                                    if (apiKeyDraft.isBlank()) {
                                        scope.launch {
                                            snackbar.showSnackbar(
                                                getString(R.string.api_key_missing),
                                            )
                                        }
                                        return@Button
                                    }
                                    Prefs.setApiKey(this@SettingsActivity, apiKeyDraft)
                                    Prefs.setAuthPreference(
                                        this@SettingsActivity,
                                        AuthPreference.API_KEY,
                                    )
                                    apiKeyDraft = ""
                                    refreshAuthUi()
                                    DiagLog.i("settings", "api key saved; pref=api_key")
                                    scope.launch {
                                        snackbar.showSnackbar(getString(R.string.api_key_saved))
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.api_key_save))
                            }
                            OutlinedButton(
                                enabled = hasApiKey,
                                onClick = {
                                    Prefs.clearApiKey(this@SettingsActivity)
                                    apiKeyDraft = ""
                                    // If key was the selected method, switch to OAuth.
                                    if (authPref == AuthPreference.API_KEY) {
                                        Prefs.setAuthPreference(
                                            this@SettingsActivity,
                                            AuthPreference.OAUTH,
                                        )
                                    }
                                    refreshAuthUi()
                                    DiagLog.i("settings", "api key cleared")
                                    scope.launch {
                                        snackbar.showSnackbar(getString(R.string.api_key_cleared))
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.api_key_clear))
                            }
                        }

                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = language,
                            onValueChange = { language = it },
                            label = { Text(stringResource(R.string.language_label)) },
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                Prefs.setLanguage(this@SettingsActivity, language)
                                Prefs.setMicMode(this@SettingsActivity, micMode)
                                Prefs.setImeDebugOverlay(this@SettingsActivity, imeDebug)
                                Prefs.setVerboseDiag(this@SettingsActivity, verbose)
                                Prefs.setAuthPreference(this@SettingsActivity, authPref)
                                DiagLog.i(
                                    "settings",
                                    "saved",
                                    "lang" to language,
                                    "mic" to micMode.prefValue,
                                    "authPref" to authPref.prefValue,
                                    "active" to activeLine(),
                                )
                                scope.launch {
                                    snackbar.showSnackbar(getString(R.string.settings_saved))
                                }
                            },
                        ) {
                            Text(stringResource(R.string.save))
                        }
                        Button(
                            onClick = {
                                requestMic.launch(Manifest.permission.RECORD_AUDIO)
                            },
                        ) {
                            Text(stringResource(R.string.grant_mic))
                        }

                        Text(
                            text = stringResource(R.string.audio_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.audio_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        MicMode.entries.forEach { mode ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = micMode == mode,
                                            onClick = { micMode = mode },
                                            role = Role.RadioButton,
                                        )
                                        .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = micMode == mode,
                                    onClick = { micMode = mode },
                                )
                                Text(
                                    text = mode.label,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.diag_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.diag_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { imeDebug = !imeDebug }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.diag_ime_overlay),
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = imeDebug, onCheckedChange = { imeDebug = it })
                        }
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { verbose = !verbose }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.diag_verbose),
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = verbose, onCheckedChange = { verbose = it })
                        }
                        Button(onClick = { shareDiagnostics() }) {
                            Text(stringResource(R.string.diag_share))
                        }
                        Button(onClick = { shareLastWav() }) {
                            Text(stringResource(R.string.diag_share_last_wav))
                        }
                        TextButton(
                            onClick = {
                                DiagLog.clear(this@SettingsActivity)
                                scope.launch {
                                    snackbar.showSnackbar(getString(R.string.diag_cleared))
                                }
                            },
                        ) {
                            Text(stringResource(R.string.diag_clear))
                        }

                        Text(
                            text = stringResource(R.string.balance_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.balance_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = {
                                try {
                                    val intent =
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://console.x.ai/team/default/billing"),
                                        )
                                    context.startActivity(intent)
                                } catch (_: android.content.ActivityNotFoundException) {
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            getString(R.string.balance_no_browser),
                                        )
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.balance_open_console))
                        }
                        Text(
                            text = stringResource(R.string.setup_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(text = stringResource(R.string.setup_body))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        XaiOauth.cancelLogin()
        loginJob?.cancel()
        super.onDestroy()
    }

    private fun shareDiagnostics() {
        try {
            DiagLog.init(this)
            val file = DiagLog.materializeForShare(this)
            if (!file.exists() || file.length() == 0L) {
                Toast.makeText(this, R.string.diag_empty, Toast.LENGTH_SHORT).show()
                return
            }
            val uri =
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file,
                )
            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diag_share_subject))
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Grok Voice Input diagnostic log\n" +
                            "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                            "sdk=${android.os.Build.VERSION.SDK_INT}\n" +
                            "auth=${XaiOauth.authModeLabel(this@SettingsActivity)}\n" +
                            "active=${XaiOauth.activeCredentialLabel(this@SettingsActivity)}\n",
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            DiagLog.i("settings", "share diagnostics", "bytes" to file.length())
            startActivity(Intent.createChooser(send, getString(R.string.diag_share_chooser)))
        } catch (e: Exception) {
            DiagLog.e("settings", "share failed", e)
            Toast.makeText(
                this,
                getString(R.string.diag_share_failed, e.message ?: "error"),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun shareLastWav() {
        try {
            val file = AudioDiagnostics.lastClipFile(this)
            if (!file.exists() || file.length() == 0L) {
                Toast.makeText(this, R.string.diag_no_last_wav, Toast.LENGTH_SHORT).show()
                return
            }
            val uri =
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file,
                )
            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = "audio/wav"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Grok Voice last.wav")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            DiagLog.i("settings", "share last.wav", "bytes" to file.length())
            startActivity(Intent.createChooser(send, getString(R.string.diag_share_chooser)))
        } catch (e: Exception) {
            DiagLog.e("settings", "share last.wav failed", e)
            Toast.makeText(
                this,
                getString(R.string.diag_share_failed, e.message ?: "error"),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
