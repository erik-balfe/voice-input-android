package dev.erik.voiceinput

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SttException(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {

    companion object {
        /**
         * Only transient transport / server blips. Auth and billing errors are final —
         * no silent retries (those hide real failures and waste seconds).
         */
        fun isRetryable(t: Throwable): Boolean {
            var current: Throwable? = t
            while (current != null) {
                val msg = current.message.orEmpty()
                val lower = msg.lowercase()
                // Never retry client / permission / billing errors.
                if (
                    lower.contains("stt 401") ||
                    lower.contains("stt 403") ||
                    lower.contains("stt 400") ||
                    lower.contains("spending-limit") ||
                    lower.contains("run out of credits") ||
                    lower.contains("invalid api key") ||
                    lower.contains("empty transcript")
                ) {
                    return false
                }
                if (
                    current is UnknownHostException ||
                    current is SocketTimeoutException ||
                    lower.contains("unable to resolve host") ||
                    lower.contains("failed to connect") ||
                    lower.contains("timeout") ||
                    lower.contains("connection reset") ||
                    lower.contains("stt 502") ||
                    lower.contains("stt 503") ||
                    lower.contains("stt 429")
                ) {
                    return true
                }
                // Generic IOException is NOT automatically retryable (covers 403 IOExceptions).
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
                    msg.contains("STT 401", ignoreCase = true) ||
                        msg.contains("OAuth session expired", ignoreCase = true) ->
                        "Auth failed — sign in with xAI again in Settings"
                    msg.contains("spending-limit", ignoreCase = true) ||
                        msg.contains("run out of credits", ignoreCase = true) ||
                        msg.contains("need a Grok subscription", ignoreCase = true) ->
                        "xAI denied STT (no credits / subscription for this login). " +
                            "Check SuperGrok on the same account, or use a funded API key."
                    msg.contains("STT 403", ignoreCase = true) ->
                        "xAI refused STT (403). Check subscription / permissions for this login."
                    msg.contains("STT set to OAuth", ignoreCase = true) ->
                        "STT uses OAuth — sign in with xAI in Settings"
                    msg.contains("STT set to API key", ignoreCase = true) ->
                        "STT uses API key — add a key in Settings or switch to OAuth"
                    msg.contains("Sign in with xAI", ignoreCase = true) ->
                        "Sign in with xAI or add an API key in Settings"
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
                    msg.isNotBlank() -> msg.take(200)
                    else -> "Transcription failed — tap Retry"
                }
            return SttException(user, t)
        }
    }
}
