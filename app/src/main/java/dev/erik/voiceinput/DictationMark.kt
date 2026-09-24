package dev.erik.voiceinput

/**
 * Attribution phrase appended after dictation.
 * Built-in defaults follow the **system** language, not the STT language pref.
 */
object DictationMark {
    data class Plan(
        val moveToEnd: Boolean,
        val text: String,
    )

    private val defaults =
        mapOf(
            "ru" to "надиктовано, распознано моделью",
            "en" to "dictated, transcribed by a model",
            "kk" to "айтылып, модель таныды",
            "ky" to "айтылып, модель тааныды",
            "de" to "diktiert, vom Modell erkannt",
            "fr" to "dicté, transcrit par un modèle",
            "es" to "dictado, transcrito por un modelo",
            "tr" to "dikte, model tanıdı",
            "zh" to "口述，模型转写",
            "ar" to "إملاء، تعرّف عليه نموذج",
        )

    fun defaultPhrase(languageTag: String): String {
        val lang =
            languageTag
                .trim()
                .lowercase()
                .substringBefore('-')
                .substringBefore('_')
        return defaults[lang] ?: defaults.getValue("en")
    }

    /**
     * How to insert [phrase] after a blank line.
     * [fieldText] null means the IME cannot read the whole field → insert at cursor.
     */
    fun plan(fieldText: String?, phrase: String): Plan {
        if (fieldText == null) {
            return Plan(moveToEnd = false, text = suffixAfterBlankLine(null, phrase))
        }
        return Plan(moveToEnd = true, text = suffixAfterBlankLine(fieldText, phrase))
    }

    fun suffixAfterBlankLine(existing: String?, phrase: String): String {
        if (existing == null) return "\n\n$phrase"
        if (existing.isEmpty()) return phrase
        val trailingNewlines = existing.length - existing.trimEnd('\n').length
        return when {
            trailingNewlines >= 2 -> phrase
            trailingNewlines == 1 -> "\n$phrase"
            else -> "\n\n$phrase"
        }
    }
}
