package com.chamika.dashtune

import org.junit.Assert.assertEquals
import org.junit.Test

class DashTuneSessionCallbackTest {

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
