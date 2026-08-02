package dev.erik.voiceinput

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.graphics.Color
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
 * Product settings only: account, dictation prefs, history.
 * Advanced (mic path, storage caps, logs) is collapsed and secondary.
 */
class SettingsActivity : ComponentActivity() {
    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)

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
            var keepIme by remember { mutableStateOf(Prefs.isKeepImeAfterStt(this)) }
            var loginStatus by remember { mutableStateOf("") }
            var loginInProgress by remember { mutableStateOf(false) }
            var probeOk by remember { mutableStateOf<Boolean?>(null) }
            var probeMessage by remember { mutableStateOf("") }
            var probeRunning by remember { mutableStateOf(false) }
            var showAdvanced by remember { mutableStateOf(false) }
            var micMode by remember { mutableStateOf(Prefs.getMicMode(this)) }
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

                        // ── Account ───────────────────────────────────
                        Text(
                            text = stringResource(R.string.settings_section_account),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = XaiOauth.activeCredentialLabel(this@SettingsActivity),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
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
                                Text(
                                    text =
                                        when (mode) {
                                            AuthPreference.OAUTH ->
                                                stringResource(R.string.auth_pref_oauth_short)
                                            AuthPreference.API_KEY ->
                                                stringResource(R.string.auth_pref_api_short)
                                        },
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }

                        if (authPref == AuthPreference.OAUTH) {
                            if (!oauthLoggedIn) {
                                Button(
                                    enabled = !loginInProgress,
                                    modifier = Modifier.fillMaxWidth(),
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
                                                    withContext(Dispatchers.IO) {
                                                        XaiOauth.pollDeviceToken(
                                                            this@SettingsActivity,
                                                            start,
                                                        )
                                                    }
                                                    loginStatus = ""
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
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        XaiOauth.logout(this@SettingsActivity)
                                        refreshAuthUi()
                                        probeOk = null
                                        probeMessage = ""
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
                            if (hasApiKey) {
                                Text(
                                    text =
                                        getString(
                                            R.string.api_key_status_present,
                                            apiKeyPreview ?: "••••",
                                        ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
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
                                            probeOk = null
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
                                probeOk = null
                                probeMessage = ""
                                scope.launch {
                                    try {
                                        val raw =
                                            withContext(Dispatchers.IO) {
                                                XaiOauth.probeStt(this@SettingsActivity)
                                            }
                                        // probeStt returns "HTTP 200 …" on success path
                                        val ok = raw.contains("HTTP 200")
                                        probeOk = ok
                                        probeMessage =
                                            if (ok) {
                                                getString(R.string.settings_test_ok)
                                            } else {
                                                // User-friendly line, not full JSON
                                                val first =
                                                    raw.lineSequence().firstOrNull().orEmpty()
                                                getString(
                                                    R.string.settings_test_fail,
                                                    first.take(120),
                                                )
                                            }
                                    } catch (e: Exception) {
                                        probeOk = false
                                        probeMessage =
                                            getString(
                                                R.string.settings_test_fail,
                                                e.message ?: "error",
                                            )
                                    } finally {
                                        probeRunning = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (probeRunning) {
                                    stringResource(R.string.oauth_probe_running)
                                } else {
                                    stringResource(R.string.settings_test_connection)
                                },
                            )
                        }
                        if (probeMessage.isNotBlank()) {
                            Text(
                                text = probeMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color =
                                    when (probeOk) {
                                        true -> Color(0xFF137333)
                                        false -> MaterialTheme.colorScheme.error
                                        null -> MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        }

                        // ── Dictation ─────────────────────────────────
                        Text(
                            text = stringResource(R.string.settings_section_dictation),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = language,
                            onValueChange = {
                                language = it
                                Prefs.setLanguage(this@SettingsActivity, it)
                            },
                            label = { Text(stringResource(R.string.language_label)) },
                            singleLine = true,
                        )
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        keepIme = !keepIme
                                        Prefs.setKeepImeAfterStt(this@SettingsActivity, keepIme)
                                    }
                                    .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_keep_ime_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(R.string.settings_keep_ime_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = keepIme,
                                onCheckedChange = {
                                    keepIme = it
                                    Prefs.setKeepImeAfterStt(this@SettingsActivity, it)
                                },
                            )
                        }

                        TextButton(
                            onClick = {
                                startActivity(
                                    Intent(this@SettingsActivity, HistoryActivity::class.java),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.history_open))
                        }

                        // ── Advanced (secondary) ──────────────────────
                        TextButton(
                            onClick = { showAdvanced = !showAdvanced },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text =
                                    if (showAdvanced) {
                                        stringResource(R.string.settings_advanced_hide)
                                    } else {
                                        stringResource(R.string.settings_advanced_link)
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (showAdvanced) {
                            Text(
                                text = stringResource(R.string.audio_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            MicMode.entries.forEach { mode ->
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = micMode == mode,
                                                onClick = {
                                                    micMode = mode
                                                    Prefs.setMicMode(
                                                        this@SettingsActivity,
                                                        mode,
                                                    )
                                                },
                                                role = Role.RadioButton,
                                            )
                                            .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = micMode == mode,
                                        onClick = {
                                            micMode = mode
                                            Prefs.setMicMode(this@SettingsActivity, mode)
                                        },
                                    )
                                    Text(
                                        text = mode.label,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }

                            Text(
                                text = stringResource(R.string.history_limits_body),
                                style = MaterialTheme.typography.bodySmall,
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
                            OutlinedButton(
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

                            TextButton(onClick = { shareDiagnostics() }) {
                                Text(stringResource(R.string.diag_share))
                            }
                        }
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
            startActivity(Intent.createChooser(send, getString(R.string.diag_share_chooser)))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.diag_share_failed, e.message ?: "error"),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
