package com.chamika.dashtune

import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException

class ApiErrorsTest {

    @Test
    fun `a 401 is recognised however deeply it is wrapped`() {
        val wrapped = RuntimeException("browse failed", IllegalStateException(InvalidStatusException(401)))

        assertTrue(ApiErrors.isUnauthorized(InvalidStatusException(401)))
        assertTrue(ApiErrors.isUnauthorized(wrapped))
    }

    @Test
    fun `other statuses are not a rejected token`() {
        assertFalse(ApiErrors.isUnauthorized(InvalidStatusException(403)))
        assertFalse(ApiErrors.isUnauthorized(InvalidStatusException(503)))
        assertFalse(ApiErrors.isUnauthorized(TimeoutException("timeout", InterruptedIOException())))
    }

    @Test
    fun `no DNS answer or a refused connection means the server is unreachable`() {
        assertTrue(ApiErrors.isOffline(TimeoutException("HTTP host unreachable", UnknownHostException("host"))))
        assertTrue(ApiErrors.isOffline(TimeoutException("Connection failed", ConnectException())))
    }

    @Test
    fun `a timeout or a server error is not just being offline`() {
        assertFalse(ApiErrors.isOffline(TimeoutException("timeout", InterruptedIOException("timeout"))))
        assertFalse(ApiErrors.isOffline(InvalidStatusException(503)))
    }
}
