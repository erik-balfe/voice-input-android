package dev.erik.voiceinput

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GrokSttClient {
    private val http =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

    fun transcribe(
        apiKey: String,
        wavBytes: ByteArray,
        language: String,
    ): String {
        val tmp = File.createTempFile("grok-stt-", ".wav")
        try {
            tmp.writeBytes(wavBytes)
            val fileBody = tmp.asRequestBody("audio/wav".toMediaType())
            val multipart =
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("format", "true")
                    .addFormDataPart("language", language)
                    .addFormDataPart("file", "recording.wav", fileBody)
                    .build()

            val request =
                Request.Builder()
                    .url("https://api.x.ai/v1/stt")
                    .header("Authorization", "Bearer $apiKey")
                    .post(multipart)
                    .build()

            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("STT ${response.code}: $body")
                }
                val json = JSONObject(body)
                val text = json.optString("text", "").trim()
                if (text.isEmpty()) {
                    throw IllegalStateException("empty transcript from STT")
                }
                return text
            }
        } finally {
            tmp.delete()
        }
    }
}