package dev.erik.voiceinput

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Minimal History surface: list, copy, retranscribe, delete.
 * See docs/FEATURE_DESIGN.md F1.
 */
class HistoryActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        DiagLog.i("history", "onCreate")
        // Recover lock/hide takes while user browses History.
        PendingSttQueue.kick(this)

        setContent {
            HistoryScreen()
        }
    }

    companion object {
        fun copyText(context: Context, text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("transcript", text))
        }

        fun retranscribe(
            context: Context,
            store: RecordingStore,
            meta: RecordingMeta,
        ): Result<String> {
            val bytes =
                store.readAudioBytes(meta.id)
                    ?: return Result.failure(SttException("No audio file for this recording"))
            val clip =
                SessionAudio.clipFromStored(
                    m4a = bytes,
                    durationMs = meta.durationMs.coerceAtLeast(VoicePipeline.MIN_DURATION_MS),
                    sampleRate = meta.sampleRate,
                )
            return try {
                val text = VoicePipeline.transcribe(context, clip)
                store.markOk(meta.id, text)
                Result.success(text)
            } catch (e: Exception) {
                val msg =
                    when (e) {
                        is SttException -> e.userMessage
                        else -> SttException.wrap(e).userMessage
                    }
                store.markFailed(meta.id, msg)
                Result.failure(if (e is SttException) e else SttException(msg))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen() {
    val context = LocalContext.current
    val store = remember { RecordingStore.fromContext(context) }
    var entries by remember { mutableStateOf(store.listNewestFirst()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        entries = store.listNewestFirst()
    }

    VoiceInputTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.history_title)) })
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.history_network_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(entries, key = { it.id }) { meta ->
                            HistoryRow(
                                meta = meta,
                                busy = busyId == meta.id,
                                onCopy = {
                                    val t = meta.text
                                    if (!t.isNullOrBlank()) {
                                        HistoryActivity.copyText(context, t)
                                        Toast.makeText(
                                            context,
                                            R.string.history_copied,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                onRetry = {
                                    busyId = meta.id
                                    scope.launch {
                                        val result =
                                            withContext(Dispatchers.IO) {
                                                HistoryActivity.retranscribe(context, store, meta)
                                            }
                                        busyId = null
                                        result
                                            .onSuccess {
                                                Toast.makeText(
                                                    context,
                                                    R.string.history_retry_ok,
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                refresh()
                                            }
                                            .onFailure { e ->
                                                val msg =
                                                    (e as? SttException)?.userMessage
                                                        ?: e.message
                                                        ?: "error"
                                                Toast.makeText(
                                                    context,
                                                    context.getString(
                                                        R.string.history_retry_fail,
                                                        msg,
                                                    ),
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                                refresh()
                                            }
                                    }
                                },
                                onDelete = {
                                    store.delete(meta.id)
                                    Toast.makeText(
                                        context,
                                        R.string.history_deleted,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    refresh()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    meta: RecordingMeta,
    busy: Boolean,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val time =
        remember(meta.createdAtMs) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(meta.createdAtMs))
        }
    val statusLabel =
        when (meta.status) {
            SessionStatus.OK -> stringResource(R.string.history_status_ok)
            SessionStatus.PENDING -> stringResource(R.string.history_status_pending)
            SessionStatus.FAILED -> stringResource(R.string.history_status_failed)
            SessionStatus.CANCELLED_SAVED -> stringResource(R.string.history_status_cancelled)
        }
    val durSec = meta.durationMs / 1000.0
    val preview =
        meta.text?.takeIf { it.isNotBlank() }
            ?: meta.error?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.history_no_text)

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = "$time · ${"%.1f".format(durSec)}s · $statusLabel",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy, enabled = meta.hasText && !busy) {
                    Text(stringResource(R.string.history_copy))
                }
                Button(onClick = onRetry, enabled = meta.canRetry && !busy) {
                    Text(
                        if (busy) {
                            stringResource(R.string.ime_processing)
                        } else {
                            stringResource(R.string.history_retry)
                        },
                    )
                }
                OutlinedButton(onClick = onDelete, enabled = !busy) {
                    Text(stringResource(R.string.history_delete))
                }
            }
        }
    }
}
