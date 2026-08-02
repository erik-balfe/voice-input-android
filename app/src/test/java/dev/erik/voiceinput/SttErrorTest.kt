package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SttErrorTest {
    @Test
    fun retryableTransportOnly() {
        assertTrue(SttException.isRetryable(UnknownHostException("dns")))
        assertTrue(SttException.isRetryable(SocketTimeoutException("timeout")))
        assertTrue(SttException.isRetryable(IOException("STT 503 bad gateway")))
        assertTrue(SttException.isRetryable(IOException("STT 429")))
        assertFalse(SttException.isRetryable(IOException("STT 403 forbidden")))
        assertFalse(SttException.isRetryable(IOException("STT 401")))
        assertFalse(SttException.isRetryable(IOException("spending-limit exceeded")))
    }

    @Test
    fun wrapMapsAuthAndNetwork() {
        val net = SttException.wrap(UnknownHostException("x"))
        assertTrue(net.userMessage.contains("network", ignoreCase = true))
        val auth = SttException.wrap(IOException("STT 401 unauthorized"))
        assertTrue(auth.userMessage.contains("Auth", ignoreCase = true) ||
            auth.userMessage.contains("sign in", ignoreCase = true))
        val passthrough = SttException("already", null)
        assertEquals(passthrough, SttException.wrap(passthrough))
    }
}
