package com.chamika.dashtune.data

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.chamika.dashtune.data.MediaRepository.Companion.SAVED_QUEUE
import com.chamika.dashtune.data.db.CachedMediaItemEntity
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.media.JellyfinMediaTree
import com.chamika.dashtune.media.MediaItemFactory
import com.chamika.dashtune.media.MediaItemFactory.Companion.HOME
import com.chamika.dashtune.media.MediaItemFactory.Companion.RADIO_ARTIST_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.RESUME_QUEUE
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_LIBRARY
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Home is cached in Room so it renders on a dead connection. These pin the rule that only a
 * build the server contributed to may be cached — a Home built offline is made of the few
 * tiles that need no server, and must never stand in for, or overwrite, the last real one —
 * and the saved queue that lets playback resume without the network.
 */
@RunWith(RobolectricTestRunner::class)
class MediaRepositoryHomeTest {

    private lateinit var dao: MediaCacheDao
    private lateinit var tree: JellyfinMediaTree
    private lateinit var repository: MediaRepository

    private val storedHome = listOf(RADIO_ARTIST_PREFIX + "a", "album-b", "album-c", RESUME_QUEUE)

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        tree = mockk(relaxed = true)
        val itemFactory = mockk<MediaItemFactory>(relaxed = true)
        every { itemFactory.streamingUri(any()) } returns "http://server/audio/stream"
        repository = MediaRepository(dao, tree, itemFactory)
    }

    @Test
    fun `a Home built with the server is served, remembered and written through`() = runTest {
        val live = listOf(tile(RESUME_QUEUE), tile(RADIO_ARTIST_PREFIX + "x"), tile(SHUFFLE_FAVOURITES))
        coEvery { tree.getChildren(HOME) } returns live
        val written = slot<Map<String, List<CachedMediaItemEntity>>>()

        assertEquals(live.ids(), repository.getChildren(HOME).ids())
        repository.getChildren(HOME)

        coVerify(exactly = 1) { tree.getChildren(HOME) }
        coVerify { dao.replaceChildren(capture(written)) }
        assertEquals(live.ids(), written.captured.getValue(HOME).map { it.mediaId })
    }

    @Test
    fun `a Home built without the server serves the cached one and does not overwrite it`() = runTest {
        coEvery { tree.getChildren(HOME) } returns listOf(tile(SHUFFLE_FAVOURITES), tile(SHUFFLE_LIBRARY))
        coEvery { dao.getChildrenByParent(HOME) } returns storedHome.rows(HOME)

        val served = repository.getChildren(HOME).ids()

        assertEquals(storedHome.filterNot { it == RESUME_QUEUE }, served)
        coVerify(exactly = 0) { dao.replaceChildren(any()) }
        coVerify(exactly = 0) { dao.deleteByParent(any()) }
    }

    @Test
    fun `the cached Home gets the current resume tile, which needs no server`() = runTest {
        val currentResume = tile(RESUME_QUEUE, title = "Today's queue")
        coEvery { tree.getChildren(HOME) } returns listOf(currentResume, tile(SHUFFLE_FAVOURITES))
        coEvery { dao.getChildrenByParent(HOME) } returns storedHome.rows(HOME)

        val served = repository.getChildren(HOME)

        assertEquals(listOf(RESUME_QUEUE) + storedHome.filterNot { it == RESUME_QUEUE }, served.ids())
        assertEquals("Today's queue", served.first().mediaMetadata.title.toString())
    }

    @Test
    fun `with no cached Home the local tiles are still served`() = runTest {
        val offline = listOf(tile(SHUFFLE_FAVOURITES), tile(SHUFFLE_LIBRARY))
        coEvery { tree.getChildren(HOME) } returns offline
        coEvery { dao.getChildrenByParent(HOME) } returns emptyList()

        assertEquals(offline.ids(), repository.getChildren(HOME).ids())
        coVerify(exactly = 0) { dao.replaceChildren(any()) }
    }

    @Test
    fun `an offline Home is retried after thirty seconds, not two minutes`() = runTest {
        coEvery { tree.getChildren(HOME) } returns listOf(tile(SHUFFLE_FAVOURITES))

        repository.getChildren(HOME)
        repository.getChildren(HOME)
        coVerify(exactly = 1) { tree.getChildren(HOME) }

        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        repository.getChildren(HOME)
        coVerify(exactly = 2) { tree.getChildren(HOME) }
    }

    @Test
    fun `invalidating Home forces a rebuild`() = runTest {
        coEvery { tree.getChildren(HOME) } returns listOf(tile(RADIO_ARTIST_PREFIX + "x"))

        repository.getChildren(HOME)
        repository.invalidateHome()
        repository.getChildren(HOME)

        coVerify(exactly = 2) { tree.getChildren(HOME) }
    }

    @Test
    fun `a failed Home build falls back to the cached Home`() = runTest {
        coEvery { tree.getChildren(HOME) } throws RuntimeException("boom")
        coEvery { dao.getChildrenByParent(HOME) } returns storedHome.rows(HOME)

        assertEquals(storedHome.filterNot { it == RESUME_QUEUE }, repository.getChildren(HOME).ids())
    }

    // --- Saved queue ---

    @Test
    fun `saving a queue writes its tracks in order under the saved-queue parent`() {
        val queue = listOf(track("t1"), track("t2"), track("t3"))
        val written = slot<Map<String, List<CachedMediaItemEntity>>>()

        repository.saveQueue(queue)

        coVerify(timeout = 5_000) { dao.replaceChildren(capture(written)) }
        assertEquals(
            listOf("t1" to 0, "t2" to 1, "t3" to 2),
            written.captured.getValue(SAVED_QUEUE).map { it.mediaId to it.sortOrder }
        )
    }

    @Test
    fun `an empty queue is never saved`() {
        repository.saveQueue(emptyList())

        Thread.sleep(200)
        coVerify(exactly = 0) { dao.replaceChildren(any()) }
    }

    @Test
    fun `the saved queue is read back from Room as playable tracks`() = runTest {
        coEvery { dao.getChildrenByParent(SAVED_QUEUE) } returns listOf("t1", "t2").rows(SAVED_QUEUE, MediaMetadata.MEDIA_TYPE_MUSIC)

        val restored = repository.getSavedQueue()

        assertEquals(listOf("t1", "t2"), restored.ids())
        assertEquals("http://server/audio/stream", restored.first().localConfiguration?.uri.toString())
    }

    @Test
    fun `the saved queue is not a content parent`() = runTest {
        // Otherwise tapping one track of it would queue the whole previous session.
        coEvery { dao.getParentIds("t1") } returns listOf(SAVED_QUEUE)

        assertNull(repository.getContentParentId("t1"))
    }

    // --- helpers ---

    private fun List<MediaItem>.ids() = map { it.mediaId }

    private fun tile(id: String, title: String = id) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .setExtras(Bundle().apply {
                    putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, "Section")
                })
                .build()
        )
        .build()

    private fun track(id: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(id)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    private fun List<String>.rows(parent: String, type: Int = MediaMetadata.MEDIA_TYPE_PLAYLIST) =
        mapIndexed { index, id ->
            CachedMediaItemEntity(
                mediaId = id,
                parentId = parent,
                title = id,
                subtitle = null,
                artUri = null,
                mediaType = type,
                isPlayable = true,
                isBrowsable = false,
                sortOrder = index,
                durationMs = null,
                isFavorite = false,
                extras = null
            )
        }
}
