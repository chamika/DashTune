package com.chamika.dashtune

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.chamika.dashtune.Constants.LOG_TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.serializer.toUUID

/**
 * Tells the server what is playing: Start when a track starts, Progress every
 * [PROGRESS_INTERVAL_MS] and on pause, Stopped when it ends or is left. The server's play
 * history (four of Home's five sections) and its resume points come from these.
 *
 * A Stopped that cannot be sent (a track ending in a tunnel) is kept in [prefs] and sent
 * once a later report gets through, or [flushPending] is called when the network returns,
 * so the play still counts. Start and Progress are not kept: they only matter live.
 *
 * Call from the main thread. The requests run in [scope], which must outlive the service, so
 * the Stopped sent as it is destroyed is not cancelled with it.
 */
class PlaybackReporter(
    private val api: ApiClient,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val isTranscoding: () -> Boolean,
) {

    companion object {
        internal const val PROGRESS_INTERVAL_MS = 20_000L
        internal const val PENDING_STOPS_PREF = "pending_playback_stops"
        internal const val MAX_PENDING_STOPS = 50
        private const val MS_TO_TICKS = 10_000L
    }

    /** The item a Start was sent for and no Stopped yet. */
    var openItemId: String? = null
        private set

    private val pendingLock = Mutex()

    fun started(itemId: String) {
        openItemId = itemId
        val method = playMethod()
        scope.launch {
            send("start") {
                api.playStateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = itemId.toUUID(),
                        canSeek = true,
                        isPaused = false,
                        isMuted = false,
                        playMethod = method,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    )
                )
            }
        }
    }

    fun progress(itemId: String, positionMs: Long, isPaused: Boolean) {
        if (openItemId != itemId) return
        val method = playMethod()
        scope.launch {
            send("progress") {
                api.playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = itemId.toUUID(),
                        canSeek = true,
                        isPaused = isPaused,
                        isMuted = false,
                        positionTicks = positionMs * MS_TO_TICKS,
                        playMethod = method,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    )
                )
            }
        }
    }

    /** Ends the open item's session at [positionMs]. Nothing to do if no Start was sent. */
    fun stopped(positionMs: Long) {
        val itemId = openItemId ?: return
        openItemId = null
        val stop = PendingStop(itemId, positionMs)
        scope.launch {
            if (!sendStop(stop)) {
                pendingLock.withLock { writePending((readPending() + stop).takeLast(MAX_PENDING_STOPS)) }
                Log.i(LOG_TAG, "Playback stop for $itemId kept to send later")
            }
        }
    }

    /** Sends the Stopped reports kept while offline, oldest first, until one fails. */
    fun flushPending() {
        scope.launch { flushPendingNow() }
    }

    private suspend fun flushPendingNow() = pendingLock.withLock {
        val pending = readPending()
        if (pending.isEmpty()) return@withLock
        val sent = pending.takeWhile { sendStop(it) }.size
        writePending(pending.drop(sent))
        if (sent > 0) FirebaseUtils.safeLog("Sent $sent playback stops kept while offline")
    }

    private suspend fun sendStop(stop: PendingStop): Boolean = send("stop") {
        api.playStateApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = stop.itemId.toUUID(),
                positionTicks = stop.positionMs * MS_TO_TICKS,
                failed = false,
            )
        )
    }

    /** True when the server took the report. Any success means the server is back, so flush. */
    private suspend fun send(what: String, request: suspend () -> Unit): Boolean = try {
        request()
        if (what != "stop" && prefs.contains(PENDING_STOPS_PREF)) flushPendingNow()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Playback $what report failed", e)
        false
    }

    private fun playMethod() = if (isTranscoding()) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY

    private data class PendingStop(val itemId: String, val positionMs: Long)

    private fun readPending(): List<PendingStop> =
        prefs.getString(PENDING_STOPS_PREF, null).orEmpty()
            .split(',')
            .mapNotNull { entry ->
                val (id, position) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                position.toLongOrNull()?.let { PendingStop(id, it) }
            }

    private fun writePending(stops: List<PendingStop>) = prefs.edit {
        if (stops.isEmpty()) remove(PENDING_STOPS_PREF)
        else putString(PENDING_STOPS_PREF, stops.joinToString(",") { "${it.itemId}:${it.positionMs}" })
    }
}
