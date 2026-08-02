package dev.erik.voiceinput

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class XaiOauthTest {
    @Test
    fun looksLikeOauthBearer() {
        assertTrue(XaiOauth.looksLikeOauthBearer("aaa.bbb.ccc"))
        assertFalse(XaiOauth.looksLikeOauthBearer("xai-abc123"))
        assertFalse(XaiOauth.looksLikeOauthBearer(""))
        assertFalse(XaiOauth.looksLikeOauthBearer("not-a-jwt"))
    }

    @Test
    fun jwtExpParses() {
        val header =
            Base64.encodeToString(
                """{"alg":"none"}""".toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        val payload =
            Base64.encodeToString(
                """{"exp":1700000000}""".toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        val token = "$header.$payload.sig"
        assertEquals(1_700_000_000L, XaiOauth.jwtExp(token))
    }

    @Test
    fun jwtExpNullForApiKey() {
        assertNull(XaiOauth.jwtExp("xai-secret-key"))
    }

    @Test
    fun accessTokenNeedsRefreshWhenNearExp() {
        val now = System.currentTimeMillis() / 1000
        val soon = now + 60 // within 1h skew
        val header =
            Base64.encodeToString(
                """{"alg":"none"}""".toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        val payload =
            Base64.encodeToString(
                """{"exp":$soon}""".toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        val token = "$header.$payload.sig"
        assertTrue(XaiOauth.accessTokenNeedsRefresh(token))

        val far = now + 10_000
        val payloadFar =
            Base64.encodeToString(
                """{"exp":$far}""".toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        assertFalse(XaiOauth.accessTokenNeedsRefresh("$header.$payloadFar.sig"))
    }
}
