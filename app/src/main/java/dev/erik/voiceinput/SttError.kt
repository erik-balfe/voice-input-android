package dev.erik.voiceinput

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SttException(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {

    companion object {
        fun isRetryable(t: Throwable): Boolean {
            var current: Throwable? = t
            while (current != null) {
                when (current) {
                    is UnknownHostException,
                    is SocketTimeoutException,
                    is IOException,
                    -> return true
                }
                val msg = current.message.orEmpty().lowercase()
                if (
                    msg.contains("unable to resolve host") ||
                    msg.contains("failed to connect") ||
                    msg.contains("timeout") ||
                    msg.contains("network")
                ) {
                    return true
                }
                if (msg.contains("stt 5") || msg.contains("stt 502") || msg.contains("stt 503") || msg.contains("stt 429")) {
                    return true
                }
                current = current.cause
            }
            return false
        }

        fun wrap(t: Throwable): SttException {
            if (t is SttException) return t
            val msg = t.message.orEmpty()
            val user =
                when {
                    t is UnknownHostException || msg.contains("Unable to resolve host", ignoreCase = true) ->
                        "No network — check connection and tap Retry"
                    t is SocketTimeoutException || msg.contains("timeout", ignoreCase = true) ->
                        "Request timed out — tap Retry"
                    msg.contains("STT 401", ignoreCase = true) ->
                        "Invalid API key — check Settings"
                    msg.contains("STT 429", ignoreCase = true) ->
                        "Rate limited — wait a moment and tap Retry"
                    msg.contains("STT 5", ignoreCase = true) ->
                        "xAI server error — tap Retry"
                    msg.contains("Missing xAI API key", ignoreCase = true) ->
                        "Add your xAI API key in Settings"
                    msg.contains("Recording too short", ignoreCase = true) ->
                        "Recording too short — speak longer"
                    msg.contains("no speech", ignoreCase = true) ->
                        "No speech detected — try again"
                    msg.isNotBlank() -> msg
                    else -> "Transcription failed — tap Retry"
                }
            return SttException(user, t)
        }
    }
}