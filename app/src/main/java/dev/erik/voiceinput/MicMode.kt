package dev.erik.voiceinput

import android.media.MediaRecorder

/**
 * Microphone / audio path preference for capture quality experiments.
 *
 * - [AUTO]: Prefer [MediaRecorder.AudioSource.VOICE_RECOGNITION] (tuned for ASR),
 *   then unprocessed, then MIC.
 * - [VOIP]: Voice-communication source + in-communication audio mode (AEC-friendly).
 * - [VOICE_RECOGNITION]: Explicit ASR path.
 * - [UNPROCESSED]: Raw mic path when available.
 * - [MIC]: Plain [MediaRecorder.AudioSource.MIC].
 */
enum class MicMode(val prefValue: String, val label: String) {
    AUTO("auto", "Auto (voice recognition)"),
    VOICE_RECOGNITION("voice_recognition", "Voice recognition (ASR)"),
    VOIP("voip", "VoIP / communication"),
    UNPROCESSED("unprocessed", "Unprocessed (raw)"),
    MIC("mic", "Default mic"),
    ;

    companion object {
        fun fromPref(value: String?): MicMode =
            entries.firstOrNull { it.prefValue == value } ?: AUTO

        fun audioSource(mode: MicMode, unprocessedSupported: Boolean): Int =
            when (mode) {
                AUTO, VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                VOIP -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                UNPROCESSED ->
                    if (unprocessedSupported) {
                        MediaRecorder.AudioSource.UNPROCESSED
                    } else {
                        MediaRecorder.AudioSource.MIC
                    }
                MIC -> MediaRecorder.AudioSource.MIC
            }

        /**
         * Preferred [MediaRecorder.AudioSource] first, then capture fallbacks so a
         * device that rejects the chosen path can still open a microphone.
         */
        fun sourcePriority(mode: MicMode, unprocessedSupported: Boolean): List<Int> {
            val preferred = audioSource(mode, unprocessedSupported)
            val fallbacks =
                listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                )
            return listOf(preferred) + fallbacks.filter { it != preferred }
        }

        fun usesCommunicationAudioMode(mode: MicMode): Boolean = mode == VOIP

        fun sourceName(source: Int): String =
            when (source) {
                MediaRecorder.AudioSource.MIC -> "MIC"
                MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
                MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
                MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
                MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                else -> "source_$source"
            }
    }
}
