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

/**
 * Product settings: History, auth, language, mic path, test STT, advanced limits, share logs.
 * Debug-only surfaces (OAuth JSON import, token dumps, last.wav share) removed from UI.
 */
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

            var authPref by remember { mutableStateOf(Prefs.getAuthPreference(this)) }
            var oauthLoggedIn by remember { mutableStateOf(XaiOauth.isLoggedIn(this)) }
            var hasApiKey by remember { mutableStateOf(Prefs.hasApiKey(this)) }
            var apiKeyPreview by remember { mutableStateOf(Prefs.apiKeyPreview(this)) }
            var apiKeyDraft by remember { mutableStateOf("") }
            var language by remember { mutableStateOf(Prefs.getLanguage(this)) }
            var micMode by remember { mutableStateOf(Prefs.getMicMode(this)) }
            var loginStatus by remember { mutableStateOf("") }
            var loginInProgress by remember { mutableStateOf(false) }
            var probeResult by remember { mutableStateOf("") }
            var probeRunning by remember { mutableStateOf(false) }
            var showAdvanced by remember { mutableStateOf(false) }
            var historyMaxItems by remember {
                mutableStateOf(Prefs.getHistoryMaxItems(this).toString())
            }
            var historyMaxMb by remember {
                mutableStateOf((Prefs.getHistoryMaxBytes(this) / (1024L * 1024L)).toString())
            }

            fun refreshAuthUi() {
                oauthLoggedIn = XaiOauth.isLoggedIn(this@SettingsActivity)
                hasApiKey = Prefs.hasApiKey(this@SettingsActivity)
                apiKeyPreview = Prefs.apiKeyPreview(this@SettingsActivity)
                authPref = Prefs.getAuthPreference(this@SettingsActivity)
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
                        verticalArrangement = Arrangement.spacedBy(14.dp),
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

                        // ── Auth ──────────────────────────────────────
                        Text(
                            text = stringResource(R.string.auth_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.auth_body_short),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = activeLine(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        AuthPreference.entries.forEach { mode ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = authPref == mode,
                                            onClick = {
                                                authPref = mode
                                                Prefs.setAuthPreference(
                                                    this@SettingsActivity,
                                                    mode,
                                                )
                                                refreshAuthUi()
                                            },
                                            role = Role.RadioButton,
                                        )
                                        .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = authPref == mode,
                                    onClick = {
                                        authPref = mode
                                        Prefs.setAuthPreference(this@SettingsActivity, mode)
                                        refreshAuthUi()
                                    },
                                )
                                Text(text = mode.label, modifier = Modifier.padding(start = 8.dp))
                            }
                        }

                        if (authPref == AuthPreference.OAUTH) {
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
                                                    loginStatus = getString(R.string.oauth_success)
                                                    refreshAuthUi()
                                                    snackbar.showSnackbar(
                                                        getString(R.string.oauth_success),
                                                    )
                                                } catch (e: Exception) {
                                                    loginStatus =
                                                        getString(
                                                            R.string.oauth_failed,
                                                            e.message ?: "error",
                                                        )
                                                } finally {
                                                    loginInProgress = false
                                                }
                                            }
                                    },
                                ) {
                                    Text(stringResource(R.string.oauth_sign_in))
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        XaiOauth.logout(this@SettingsActivity)
                                        refreshAuthUi()
                                        loginStatus = ""
                                        scope.launch {
                                            snackbar.showSnackbar(
                                                getString(R.string.oauth_status_out),
                                            )
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.oauth_sign_out))
                                }
                            }
                            if (loginStatus.isNotBlank()) {
                                Text(
                                    text = loginStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        if (authPref == AuthPreference.API_KEY) {
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
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = apiKeyDraft,
                                onValueChange = { apiKeyDraft = it },
                                label = { Text(stringResource(R.string.api_key_hint)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (apiKeyDraft.isNotBlank()) {
                                            Prefs.setApiKey(this@SettingsActivity, apiKeyDraft)
                                            apiKeyDraft = ""
                                            Prefs.setAuthPreference(
                                                this@SettingsActivity,
                                                AuthPreference.API_KEY,
                                            )
                                            refreshAuthUi()
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    getString(R.string.api_key_saved),
                                                )
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.api_key_save))
                                }
                                if (hasApiKey) {
                                    TextButton(
                                        onClick = {
                                            Prefs.clearApiKey(this@SettingsActivity)
                                            refreshAuthUi()
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    getString(R.string.api_key_cleared),
                                                )
                                            }
                                        },
                                    ) {
                                        Text(stringResource(R.string.api_key_clear))
                                    }
                                }
                            }
                        }

                        Button(
                            enabled = !probeRunning && XaiOauth.hasAnyAuth(this@SettingsActivity),
                            onClick = {
                                probeRunning = true
                                probeResult = getString(R.string.oauth_probe_running)
                                scope.launch {
                                    try {
                                        probeResult =
                                            withContext(Dispatchers.IO) {
                                                XaiOauth.probeStt(this@SettingsActivity)
                                            }
                                    } catch (e: Exception) {
                                        probeResult =
                                            getString(
                                                R.string.oauth_probe_fail,
                                                e.message ?: "error",
                                            )
                                    } finally {
                                        probeRunning = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.oauth_probe_stt))
                        }
                        if (probeResult.isNotBlank()) {
                            Text(
                                text = probeResult,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        // ── Language ──────────────────────────────────
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
                                Prefs.setAuthPreference(this@SettingsActivity, authPref)
                                scope.launch {
                                    snackbar.showSnackbar(getString(R.string.settings_saved))
                                }
                            },
                        ) {
                            Text(stringResource(R.string.save))
                        }

                        // ── Mic ───────────────────────────────────────
                        Text(
                            text = stringResource(R.string.audio_title),
                            style = MaterialTheme.typography.titleMedium,
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
                                        .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = micMode == mode,
                                    onClick = { micMode = mode },
                                )
                                Text(text = mode.label, modifier = Modifier.padding(start = 8.dp))
                            }
                        }

                        // ── Advanced ──────────────────────────────────
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { showAdvanced = !showAdvanced }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.advanced_show),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = showAdvanced,
                                onCheckedChange = { showAdvanced = it },
                            )
                        }
                        if (showAdvanced) {
                            Text(
                                text = stringResource(R.string.history_limits_body),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = historyMaxItems,
                                onValueChange = {
                                    historyMaxItems = it.filter { c -> c.isDigit() }
                                },
                                label = { Text(stringResource(R.string.history_max_items)) },
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = historyMaxMb,
                                onValueChange = {
                                    historyMaxMb = it.filter { c -> c.isDigit() }
                                },
                                label = { Text(stringResource(R.string.history_max_mb)) },
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Button(
                                onClick = {
                                    val items =
                                        historyMaxItems.toIntOrNull()?.coerceIn(1, 500) ?: 50
                                    val mb =
                                        historyMaxMb.toLongOrNull()?.coerceIn(10L, 5000L) ?: 500L
                                    Prefs.setHistoryMaxItems(this@SettingsActivity, items)
                                    Prefs.setHistoryMaxBytes(
                                        this@SettingsActivity,
                                        mb * 1024L * 1024L,
                                    )
                                    historyMaxItems = items.toString()
                                    historyMaxMb = mb.toString()
                                    RecordingStore.fromContext(this@SettingsActivity)
                                        .prune(items, mb * 1024L * 1024L)
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            getString(R.string.history_limits_saved),
                                        )
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.history_limits_save))
                            }

                            Button(
                                onClick = { shareDiagnostics() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.diag_share))
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
                        }

                        Text(
                            text = stringResource(R.string.setup_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.setup_body_short),
                            style = MaterialTheme.typography.bodySmall,
                        )
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
}
