package dev.erik.voiceinput

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class SttRetryTest {
    @Test
    fun billing403NotRetryable() {
        val e =
            IOException(
                "STT 403: {\"error\":\"You have run out of credits\",\"WKE\":\"spending-limit\"}",
            )
        assertFalse(SttException.isRetryable(e))
    }

    @Test
    fun auth401NotRetryable() {
        assertFalse(SttException.isRetryable(IOException("STT 401: unauthorized")))
    }

    @Test
    fun timeoutIsRetryable() {
        assertTrue(SttException.isRetryable(SocketTimeoutException("timeout")))
    }

    @Test
    fun server503IsRetryable() {
        assertTrue(SttException.isRetryable(IOException("STT 503: unavailable")))
    }
}
