package com.chamika.dashtune

import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException

// Robolectric for the DataSpec Uri inside an ExoPlayer stream error.
@RunWith(RobolectricTestRunner::class)
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

    private fun streamError(status: Int) = PlaybackException(
        "Source error",
        HttpDataSource.InvalidResponseCodeException(
            status, null, null, emptyMap(), DataSpec(Uri.parse("https://jf.example/Audio/1/universal")), ByteArray(0)
        ),
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    )

    @Test
    fun `a stream refused with 401 is a rejected token`() {
        assertTrue(ApiErrors.isUnauthorizedStream(streamError(401)))
    }

    @Test
    fun `a stream that failed with another status is not`() {
        assertFalse(ApiErrors.isUnauthorizedStream(streamError(404)))
        assertFalse(ApiErrors.isUnauthorizedStream(streamError(500)))
    }
}
