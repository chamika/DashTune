package com.chamika.dashtune

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * Classifies failures from Jellyfin calls. The SDK and our own timeout wrappers nest the real
 * cause, so each check walks the cause chain.
 */
object ApiErrors {

    /** The server rejected the access token: it was revoked, or the user was deleted. */
    fun isUnauthorized(e: Throwable): Boolean =
        causes(e).any { it is InvalidStatusException && it.status == 401 }

    /** The server refused an audio stream with HTTP 401: the same dead token, seen by ExoPlayer. */
    @OptIn(UnstableApi::class)
    fun isUnauthorizedStream(e: Throwable): Boolean =
        causes(e).any { it is HttpDataSource.InvalidResponseCodeException && it.responseCode == 401 }

    /**
     * The server could not be reached at all: no DNS answer or a refused connection. Expected
     * whenever the car is out of coverage, so these are breadcrumbs, not Crashlytics issues.
     */
    fun isOffline(e: Throwable): Boolean =
        causes(e).any { it is UnknownHostException || it is ConnectException }

    private fun causes(e: Throwable) = generateSequence(e) { it.cause }.take(10)
}
