package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthPreferenceTest {
    @Test
    fun fromPref() {
        assertEquals(AuthPreference.OAUTH, AuthPreference.fromPref("oauth"))
        assertEquals(AuthPreference.API_KEY, AuthPreference.fromPref("api_key"))
        assertEquals(AuthPreference.OAUTH, AuthPreference.fromPref(null))
        assertEquals(AuthPreference.OAUTH, AuthPreference.fromPref("nope"))
    }
}
