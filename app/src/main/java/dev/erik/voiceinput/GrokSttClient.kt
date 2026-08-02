package dev.erik.voiceinput

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GrokSttClient(
    private val maxRetries: Int = 3,
    private val initialBackoffMs: Long = 500L,
) {
    private val http =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

    data class AudioUpload(
        val bytes: ByteArray,
        val fileName: String,
        val mimeType: String,
    )

    fun transcribe(
        apiKey: String,
        audio: AudioUpload,
        language: String,
        onPhase: ((String) -> Unit)? = null,
    ): String {
        var lastError: Throwable? = null
        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                val backoff = initialBackoffMs * (1L shl (attempt - 1))
                DiagLog.w(
                    "stt",
                    "retry",
                    "attempt" to attempt,
                    "backoffMs" to backoff,
                    "last" to (lastError?.message ?: ""),
                )
                onPhase?.invoke("Retry $attempt/${maxRetries}…")
                Thread.sleep(backoff)
            }
            try {
                return transcribeOnce(apiKey, audio, language, attempt, onPhase)
            } catch (e: Exception) {
                lastError = e
                DiagLog.e("stt", "attempt failed", e, "attempt" to attempt)
                if (!SttException.isRetryable(e) || attempt == maxRetries) {
                    break
                }
            }
        }
        throw SttException.wrap(lastError ?: IllegalStateException("STT failed"))
    }

    /** Back-compat: WAV bytes. */
    fun transcribe(
        apiKey: String,
        wavBytes: ByteArray,
        language: String,
        onPhase: ((String) -> Unit)? = null,
    ): String =
        transcribe(
            apiKey,
            AudioUpload(wavBytes, "recording.wav", "audio/wav"),
            language,
            onPhase,
        )

    private fun transcribeOnce(
        apiKey: String,
        audio: AudioUpload,
        language: String,
        attempt: Int,
        onPhase: ((String) -> Unit)?,
    ): String {
        val total =
            DiagLog.start(
                "stt",
                "http",
                "attempt" to attempt,
                "bytes" to audio.bytes.size,
                "file" to audio.fileName,
                "mime" to audio.mimeType,
                "lang" to language,
                "keyPrefix" to apiKey.take(6),
            )
        onPhase?.invoke("Processing…")
        DiagLog.setStatus(
            "STT processing",
            "${audio.fileName} ${formatBytes(audio.bytes.size)} lang=$language",
        )

        val fileBody = audio.bytes.toRequestBody(audio.mimeType.toMediaType())
        val multipart =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("format", "true")
                .addFormDataPart("language", language)
                .addFormDataPart("file", audio.fileName, fileBody)
                .build()

        val request =
            Request.Builder()
                .url("https://api.x.ai/v1/stt")
                .header("Authorization", "Bearer $apiKey")
                .post(multipart)
                .build()

        val callStart = System.nanoTime()
        http.newCall(request).execute().use { response ->
            val httpMs = (System.nanoTime() - callStart) / 1_000_000L
            ProcessingProgress.noteUpload(audio.bytes.size, httpMs)
            onPhase?.invoke("Processing…")
            val body = response.body?.string().orEmpty()
            DiagLog.i(
                "stt",
                "response",
                "code" to response.code,
                "httpMs" to httpMs,
                "bodyBytes" to body.length,
                "attempt" to attempt,
                "uploadBytes" to audio.bytes.size,
            )
            if (!response.isSuccessful) {
                DiagLog.w(
                    "stt",
                    "http error body",
                    "code" to response.code,
                    "body" to body.take(400),
                )
                total.end("ok" to false, "code" to response.code, "httpMs" to httpMs)
                throw IOException("STT ${response.code}: $body")
            }
            val json = JSONObject(body)
            val text = json.optString("text", "")
            if (text.trim().isEmpty()) {
                total.end("ok" to false, "httpMs" to httpMs, "empty" to true)
                throw IllegalStateException("empty transcript from STT")
            }
            total.end(
                "ok" to true,
                "httpMs" to httpMs,
                "textLen" to text.length,
                "preview" to text.take(80),
                "uploadBytes" to audio.bytes.size,
            )
            onPhase?.invoke("Done ${httpMs}ms")
            return text
        }
    }

    private fun formatBytes(n: Int): String =
        when {
            n < 1024 -> "${n}B"
            n < 1024 * 1024 -> "${n / 1024}KB"
            else -> "%.1fMB".format(n / (1024.0 * 1024.0))
        }
}
