package com.chamika.dashtune

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.preference.PreferenceManager
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_IDS_PREF
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_INDEX_PREF
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_TRACK_POSITON_MS_PREF
import com.chamika.dashtune.auth.JellyfinAccountManager
import com.chamika.dashtune.data.MediaRepository.Companion.SAVED_QUEUE
import com.chamika.dashtune.data.db.CachedMediaItemEntity
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RESUME_QUEUE
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.UniversalAudioApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.InterruptedIOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutionException

/**
 * Acceptance tests for the in-car playback failures: resuming at start-up, the Home resume
 * tile, and tapping a container while the server cannot be reached. The server is always
 * unreachable here — every Jellyfin call fails — because that is the state the car is in
 * during the first minute of a drive.
 */
@RunWith(RobolectricTestRunner::class)
class DashTuneSessionCallbackPlaybackTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var dao: MediaCacheDao
    private lateinit var player: Player
    private lateinit var session: MediaSession
    private lateinit var callback: DashTuneSessionCallback

    private val a = UUID.randomUUID().toString()
    private val b = UUID.randomUUID().toString()
    private val c = UUID.randomUUID().toString()
    private val d = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().commit()

        dao = mockk(relaxed = true)
        // Nothing is cached unless a test says so (a relaxed mock would invent a row).
        coEvery { dao.getItem(any()) } returns null
        player = mockk(relaxed = true)
        every { player.mediaItemCount } returns 0
        session = mockk(relaxed = true)
        every { session.player } returns player

        callback = DashTuneSessionCallback(service(), accounts(), unreachableServer(), dao)
        // Initialises the media tree the way the head unit's first browse does.
        callback.onGetLibraryRoot(
            mockk<MediaLibraryService.MediaLibrarySession>(relaxed = true),
            mockk(relaxed = true),
            null
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    // --- Resuming the saved queue ---

    @Test
    fun `the saved queue is restored from Room without the network`() {
        saveQueue(listOf(a, b, c), index = 1, positionMs = 42_000)
        givenSavedQueueRows(a, b, c)

        val restored = awaitOnMain(resume())

        assertEquals(listOf(a, b, c), restored.mediaItems.map { it.mediaId })
        assertEquals(1, restored.startIndex)
        assertEquals(42_000L, restored.startPositionMs)
    }

    @Test
    fun `a track that cannot be found is skipped rather than failing the whole restore`() {
        saveQueue(listOf(a, b, c), index = 0)
        givenSavedQueueRows(a, b)

        val restored = awaitOnMain(resume())

        assertEquals(listOf(a, b), restored.mediaItems.map { it.mediaId })
        assertEquals(0, restored.startIndex)
    }

    @Test
    fun `when the saved track is gone the restore starts at the next one from its beginning`() {
        saveQueue(listOf(a, b, c, d), index = 1, positionMs = 42_000)
        givenSavedQueueRows(a, c, d)

        val restored = awaitOnMain(resume())

        assertEquals(listOf(a, c, d), restored.mediaItems.map { it.mediaId })
        assertEquals(c, restored.mediaItems[restored.startIndex].mediaId)
        assertEquals(0L, restored.startPositionMs)
    }

    @Test
    fun `a track queued twice is restored twice, at the saved copy`() {
        saveQueue(listOf(a, b, a), index = 2)
        givenSavedQueueRows(a, b)

        val restored = awaitOnMain(resume())

        assertEquals(listOf(a, b, a), restored.mediaItems.map { it.mediaId })
        assertEquals(2, restored.startIndex)
    }

    @Test
    fun `a slow restore does not replace a queue the user set in the meantime`() {
        saveQueue(listOf(a, b), index = 0)
        givenSavedQueueRows(a, b)
        every { player.mediaItemCount } returns 5

        assertFailsWithIllegalState(resume())
    }

    @Test
    fun `resumption fails instead of returning an empty queue`() {
        assertFailsWithIllegalState(resume())
    }

    // --- Tapping something that cannot be resolved ---

    @Test
    fun `an uncached playlist tapped offline fails, says so, and keeps the saved queue`() {
        saveQueue(listOf(a, b), index = 0)
        val playlistId = UUID.randomUUID().toString()
        // The playlist row is cached from browsing the Playlists tab; its tracks are not.
        coEvery { dao.getItem(playlistId) } returns row(playlistId, PLAYLISTS, 0, MediaMetadata.MEDIA_TYPE_PLAYLIST)
        coEvery { dao.getChildrenByParent(playlistId) } returns emptyList()

        val future = callback.onSetMediaItems(
            session, mockk(relaxed = true), listOf(item(playlistId)), C.INDEX_UNSET, C.TIME_UNSET
        )

        assertTrue(runCatching { awaitOnMain(future) }.isFailure)
        assertEquals("$a,$b", prefs.getString(PLAYLIST_IDS_PREF, null))
        verify { session.sendError(any<SessionError>()) }
    }

    @Test
    fun `the resume tile fails rather than clearing the player when nothing can be restored`() {
        val future = callback.onSetMediaItems(
            session, mockk(relaxed = true), listOf(item(RESUME_QUEUE)), C.INDEX_UNSET, C.TIME_UNSET
        )

        assertTrue(runCatching { awaitOnMain(future) }.isFailure)
    }

    @Test
    fun `setting a queue saves its tracks for the next cold start`() {
        val playlistId = UUID.randomUUID().toString()
        coEvery { dao.getItem(playlistId) } returns row(playlistId, PLAYLISTS, 0, MediaMetadata.MEDIA_TYPE_PLAYLIST)
        coEvery { dao.getChildrenByParent(playlistId) } returns listOf(
            row(c, playlistId, 0),
            row(d, playlistId, 1)
        )
        coEvery { dao.getItem(c) } returns row(c, playlistId, 0)
        coEvery { dao.getItem(d) } returns row(d, playlistId, 1)

        val set = awaitOnMain(
            callback.onSetMediaItems(
                session, mockk(relaxed = true), listOf(item(playlistId)), C.INDEX_UNSET, C.TIME_UNSET
            )
        )

        assertEquals(listOf(c, d), set.mediaItems.map { it.mediaId })
        assertEquals("$c,$d", prefs.getString(PLAYLIST_IDS_PREF, null))
        coVerify(timeout = 5_000) {
            dao.replaceChildren(match { it[SAVED_QUEUE]?.map { row -> row.mediaId } == listOf(c, d) })
        }
    }

    // --- helpers ---

    private fun resume() = callback.onPlaybackResumption(session, mockk(relaxed = true), true)

    private fun saveQueue(ids: List<String>, index: Int, positionMs: Long = 0) {
        prefs.edit()
            .putString(PLAYLIST_IDS_PREF, ids.joinToString(","))
            .putInt(PLAYLIST_INDEX_PREF, index)
            .putLong(PLAYLIST_TRACK_POSITON_MS_PREF, positionMs)
            .commit()
    }

    private fun givenSavedQueueRows(vararg ids: String) {
        coEvery { dao.getChildrenByParent(SAVED_QUEUE) } returns
            ids.mapIndexed { index, id -> row(id, SAVED_QUEUE, index) }
    }

    private fun row(
        id: String,
        parent: String,
        order: Int,
        type: Int = MediaMetadata.MEDIA_TYPE_MUSIC
    ) = CachedMediaItemEntity(
        mediaId = id,
        parentId = parent,
        title = "Item $id",
        subtitle = null,
        artUri = null,
        mediaType = type,
        isPlayable = true,
        isBrowsable = false,
        sortOrder = order,
        durationMs = null,
        isFavorite = false,
        extras = null
    )

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun service(): DashTuneMusicService {
        val service = mockk<DashTuneMusicService>(relaxed = true)
        every { service.packageName } returns context.packageName
        every { service.getSharedPreferences(any(), any()) } answers {
            context.getSharedPreferences(firstArg(), secondArg())
        }
        every { service.resources } returns context.resources
        every { service.getString(any()) } answers { context.getString(firstArg()) }
        return service
    }

    private fun accounts(): JellyfinAccountManager {
        val accounts = mockk<JellyfinAccountManager>(relaxed = true)
        every { accounts.isAuthenticated } returns true
        return accounts
    }

    /** Every lookup fails the way a stalled car connection does, without being retried. */
    private fun unreachableServer(): ApiClient {
        val api = mockk<ApiClient>(relaxed = true)
        val userLibraryApi = mockk<UserLibraryApi>()
        val itemsApi = mockk<ItemsApi>()
        val offline = TimeoutException("Connection timed out or was interrupted", InterruptedIOException("timeout"))
        coEvery { userLibraryApi.getItem(any(), any()) } throws offline
        coEvery { itemsApi.getItems(sortBy = any(), parentId = any(), startIndex = any(), limit = any()) } throws offline
        every { api.userLibraryApi } returns userLibraryApi
        every { api.itemsApi } returns itemsApi
        // Stream URLs are built locally; a relaxed mock answers with an empty one.
        every { api.universalAudioApi } returns mockk<UniversalAudioApi>(relaxed = true)
        return api
    }

    private fun assertFailsWithIllegalState(future: ListenableFuture<*>) {
        try {
            awaitOnMain(future)
            throw AssertionError("expected the future to fail")
        } catch (e: ExecutionException) {
            assertTrue("unexpected cause ${e.cause}", e.cause is IllegalStateException)
        }
    }

    /** Futures resolve on the main looper; advance its clock so coroutine timeouts can fire too. */
    private fun <T> awaitOnMain(future: ListenableFuture<T>, timeoutMs: Long = 30_000): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!future.isDone && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(5)
        }
        return future.get()
    }
}
