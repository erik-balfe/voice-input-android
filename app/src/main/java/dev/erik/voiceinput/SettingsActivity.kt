package dev.erik.voiceinput

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            var apiKey by remember { mutableStateOf(Prefs.getApiKey(this) ?: "") }
            var language by remember { mutableStateOf(Prefs.getLanguage(this)) }

            MaterialTheme {
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
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(stringResource(R.string.api_key_hint)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = language,
                            onValueChange = { language = it },
                            label = { Text(stringResource(R.string.language_label)) },
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                if (apiKey.isBlank()) {
                                    scope.launch {
                                        snackbar.showSnackbar(getString(R.string.api_key_missing))
                                    }
                                    return@Button
                                }
                                Prefs.setApiKey(this@SettingsActivity, apiKey)
                                Prefs.setLanguage(this@SettingsActivity, language)
                                scope.launch {
                                    snackbar.showSnackbar(getString(R.string.api_key_saved))
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
                            text = stringResource(R.string.setup_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(text = stringResource(R.string.setup_body))
                    }
                }
            }
        }
    }
}