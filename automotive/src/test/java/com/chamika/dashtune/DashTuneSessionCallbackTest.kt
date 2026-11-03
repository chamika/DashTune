package com.chamika.dashtune

import androidx.media3.common.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DashTuneSessionCallbackTest {

    // --- Favourites notification after a rating ---

    @Test
    fun `a refresh within the timeout notifies before the wait returns`() = runBlocking {
        val notified = mutableListOf<Int>()

        DashTuneSessionCallback.notifyWhenRefreshed(
            this, CompletableDeferred(listOf(MediaItem.EMPTY, MediaItem.EMPTY)), 5_000
        ) { notified += it }

        assertEquals(listOf(2), notified)
    }

    @Test
    fun `a refresh slower than the timeout returns at the timeout and notifies late`() = runBlocking {
        val refresh = CompletableDeferred<List<MediaItem>>()
        val notified = CompletableDeferred<Int>()

        DashTuneSessionCallback.notifyWhenRefreshed(this, refresh, 10) { notified.complete(it) }
        assertFalse("Must not notify before the refresh is done", notified.isCompleted)

        refresh.complete(listOf(MediaItem.EMPTY))
        assertEquals(1, withTimeout(5_000) { notified.await() })
    }

    @Test
    fun `a refresh replaced by a newer one returns without notifying`() = runBlocking {
        val refresh = CompletableDeferred<List<MediaItem>>()
        refresh.cancel()
        var notified = false

        DashTuneSessionCallback.notifyWhenRefreshed(this, refresh, 5_000) { notified = true }

        assertFalse(notified)
    }

    @Test
    fun `a released session is not notified by a late refresh`() = runBlocking {
        val released = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val refresh = CompletableDeferred<List<MediaItem>>()
        var notified = false

        DashTuneSessionCallback.notifyWhenRefreshed(released, refresh, 10) { notified = true }
        released.cancel()
        refresh.complete(emptyList())
        delay(50)

        assertFalse(notified)
    }

    // --- Companion constant tests ---

    @Test
    fun `LOGIN_COMMAND uses correct namespace`() {
        assertEquals("com.chamika.dashtune.COMMAND.LOGIN", DashTuneSessionCallback.LOGIN_COMMAND)
    }

    @Test
    fun `REPEAT_COMMAND uses correct namespace`() {
        assertEquals("com.chamika.dashtune.COMMAND.REPEAT", DashTuneSessionCallback.REPEAT_COMMAND)
    }

    @Test
    fun `SHUFFLE_COMMAND uses correct namespace`() {
        assertEquals("com.chamika.dashtune.COMMAND.SHUFFLE", DashTuneSessionCallback.SHUFFLE_COMMAND)
    }

    @Test
    fun `SYNC_COMMAND uses correct namespace`() {
        assertEquals("com.chamika.dashtune.COMMAND.SYNC", DashTuneSessionCallback.SYNC_COMMAND)
    }

    @Test
    fun `PLAYLIST_IDS_PREF has expected value`() {
        assertEquals("playlistIds", DashTuneSessionCallback.PLAYLIST_IDS_PREF)
    }

    @Test
    fun `PLAYLIST_INDEX_PREF has expected value`() {
        assertEquals("playlistIndex", DashTuneSessionCallback.PLAYLIST_INDEX_PREF)
    }

    @Test
    fun `PLAYLIST_TRACK_POSITON_MS_PREF has expected value`() {
        assertEquals("playlistTrackPositionMs", DashTuneSessionCallback.PLAYLIST_TRACK_POSITON_MS_PREF)
    }

    @Test
    fun `all command constants are unique`() {
        val commands = setOf(
            DashTuneSessionCallback.LOGIN_COMMAND,
            DashTuneSessionCallback.REPEAT_COMMAND,
            DashTuneSessionCallback.SHUFFLE_COMMAND,
            DashTuneSessionCallback.SYNC_COMMAND
        )
        assertEquals(4, commands.size)
    }

    @Test
    fun `all pref key constants are unique`() {
        val keys = setOf(
            DashTuneSessionCallback.PLAYLIST_IDS_PREF,
            DashTuneSessionCallback.PLAYLIST_INDEX_PREF,
            DashTuneSessionCallback.PLAYLIST_TRACK_POSITON_MS_PREF
        )
        assertEquals(3, keys.size)
    }
}
