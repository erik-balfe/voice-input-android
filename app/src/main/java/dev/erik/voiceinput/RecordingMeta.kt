package dev.erik.voiceinput

/**
 * On-disk session record (see docs/FEATURE_DESIGN.md).
 * Product audio is M4A only; [audioBytes] is the size of that file.
 */
enum class SessionStatus {
    PENDING,
    OK,
    FAILED,
    CANCELLED_SAVED,
    ;

    val wire: String
        get() =
            when (this) {
                PENDING -> "pending"
                OK -> "ok"
                FAILED -> "failed"
                CANCELLED_SAVED -> "cancelled_saved"
            }

    companion object {
        fun fromWire(value: String?): SessionStatus =
            when (value?.lowercase()) {
                "ok" -> OK
                "failed" -> FAILED
                "cancelled_saved" -> CANCELLED_SAVED
                else -> PENDING
            }
    }
}

data class RecordingMeta(
    val id: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val status: SessionStatus,
    val language: String,
    val sourcePackage: String? = null,
    val text: String? = null,
    val error: String? = null,
    val audioBytes: Long = 0L,
    val sampleRate: Int = 16_000,
) {
    val hasText: Boolean get() = !text.isNullOrBlank()
    val canRetry: Boolean get() = audioBytes > 0L
}
