package dev.erik.voiceinput

/**
 * Which credential STT must use. Explicit — no silent cross-fallback.
 *
 * - [OAUTH]: subscription-quota bearer only. Fail if not signed in.
 * - [API_KEY]: prepaid API key only. Fail if key missing.
 */
enum class AuthPreference(val prefValue: String, val label: String) {
    OAUTH("oauth", "OAuth — SuperGrok / subscription quota"),
    API_KEY("api_key", "API key — pay-per-token credits"),
    ;

    companion object {
        fun fromPref(value: String?): AuthPreference =
            entries.firstOrNull { it.prefValue == value } ?: OAUTH
    }
}
