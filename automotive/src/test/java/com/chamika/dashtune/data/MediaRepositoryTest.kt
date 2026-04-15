package com.chamika.dashtune.data

import android.os.Bundle
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.chamika.dashtune.data.db.CachedMediaItemEntity
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.media.JellyfinMediaTree
import com.chamika.dashtune.media.MediaItemFactory
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaRepositoryTest {

    private lateinit var dao: MediaCacheDao
    private lateinit var tree: JellyfinMediaTree
    private lateinit var itemFactory: MediaItemFactory
    private lateinit var repository: MediaRepository

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        tree = mockk(relaxed = true)
        itemFactory = mockk(relaxed = true)
        every { itemFactory.streamingUri(any()) } returns "http://server/audio/stream"
        repository = MediaRepository(dao, tree, itemFactory)
    }

    // --- getItem tests ---

    @Test
    fun `getItem returns cached item when DAO has data`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "track-1",
            parentId = "album-1",
            title = "My Track",
            subtitle = "Artist Name",
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = 180000L,
            isFavorite = false,
            extras = null
        )

        coEvery { dao.getItem("track-1") } returns entity

        val result = repository.getItem("track-1")

        assertEquals("track-1", result.mediaId)
        assertEquals("My Track", result.mediaMetadata.title.toString())
        assertEquals("Artist Name", result.mediaMetadata.albumArtist.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, result.mediaMetadata.mediaType)
        assertTrue(result.mediaMetadata.isPlayable == true)
        assertFalse(result.mediaMetadata.isBrowsable == true)
    }

    @Test
    fun `getItem falls back to tree when not cached`() = runTest {
        val treeItem = MediaItem.Builder()
            .setMediaId("track-1")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Tree Track")
                    .setIsPlayable(true)
                    .build()
            )
            .build()

        coEvery { dao.getItem("track-1") } returns null
        coEvery { tree.getItem("track-1") } returns treeItem

        val result = repository.getItem("track-1")

        assertEquals("track-1", result.mediaId)
        assertEquals("Tree Track", result.mediaMetadata.title.toString())
    }

    @Test
    fun `getItem returns static item for ROOT_ID`() = runTest {
        val rootItem = MediaItem.Builder()
            .setMediaId("ROOT_ID")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Root")
                    .build()
            )
            .build()

        coEvery { tree.getItem("ROOT_ID") } returns rootItem

        val result = repository.getItem("ROOT_ID")

        assertEquals("ROOT_ID", result.mediaId)
    }

    // --- Cache roundtrip tests ---

    @Test
    fun `cached playable music item gets streaming URI`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "track-1",
            parentId = "album-1",
            title = "Track",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = null,
            isFavorite = false,
            extras = null
        )

        coEvery { dao.getItem("track-1") } returns entity

        val result = repository.getItem("track-1")

        assertNotNull(result.localConfiguration)
        assertEquals("http://server/audio/stream", result.localConfiguration?.uri.toString())
    }

    @Test
    fun `cached audiobook item gets streaming URI`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "book-1",
            parentId = "books",
            title = "Audiobook",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = null,
            isFavorite = false,
            extras = """{"is_audiobook":true}"""
        )

        coEvery { dao.getItem("book-1") } returns entity

        val result = repository.getItem("book-1")

        assertNotNull(result.localConfiguration)
    }

    @Test
    fun `cached non-playable item has no URI`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "artist-1",
            parentId = "root",
            title = "Artist",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
            isPlayable = false,
            isBrowsable = true,
            sortOrder = 0,
            durationMs = null,
            isFavorite = false,
            extras = null
        )

        coEvery { dao.getItem("artist-1") } returns entity

        val result = repository.getItem("artist-1")

        assertNull(result.localConfiguration)
    }

    @Test
    fun `cached item preserves extras including audiobook flag`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "chapter-1",
            parentId = "book-1",
            title = "Chapter 1",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = null,
            isFavorite = false,
            extras = """{"is_audiobook":true,"PARENT_KEY":"book-1"}"""
        )

        coEvery { dao.getItem("chapter-1") } returns entity

        val result = repository.getItem("chapter-1")

        assertTrue(result.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
        assertEquals("book-1", result.mediaMetadata.extras?.getString(PARENT_KEY))
    }

    @Test
    fun `cached item preserves favorite status`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "track-1",
            parentId = "fav",
            title = "Fav Track",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = null,
            isFavorite = true,
            extras = null
        )

        coEvery { dao.getItem("track-1") } returns entity

        val result = repository.getItem("track-1")

        val rating = result.mediaMetadata.userRating as? HeartRating
        assertNotNull(rating)
        assertTrue(rating!!.isHeart)
    }

    @Test
    fun `cached item preserves duration`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "track-1",
            parentId = "album-1",
            title = "Track",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = 240000L,
            isFavorite = false,
            extras = null
        )

        coEvery { dao.getItem("track-1") } returns entity

        val result = repository.getItem("track-1")

        assertEquals(240000L, result.mediaMetadata.durationMs)
    }

    @Test
    fun `cached playlist item has no streaming URI`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "playlist-1",
            parentId = "playlists",
            title = "My Playlist",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = null,
            isFavorite = false,
            extras = null
        )

        coEvery { dao.getItem("playlist-1") } returns entity

        val result = repository.getItem("playlist-1")

        assertNull(result.localConfiguration)
    }

    // --- getChildren tests ---

    @Test
    fun `getChildren returns cached items when available`() = runTest {
        val entities = listOf(
            CachedMediaItemEntity(
                mediaId = "track-1",
                parentId = "album-1",
                title = "Track 1",
                subtitle = null,
                artUri = null,
                mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
                isPlayable = true,
                isBrowsable = false,
                sortOrder = 0,
                durationMs = null,
                isFavorite = false,
                extras = null
            ),
            CachedMediaItemEntity(
                mediaId = "track-2",
                parentId = "album-1",
                title = "Track 2",
                subtitle = null,
                artUri = null,
                mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
                isPlayable = true,
                isBrowsable = false,
                sortOrder = 1,
                durationMs = null,
                isFavorite = false,
                extras = null
            )
        )

        coEvery { dao.getChildrenByParent("album-1") } returns entities

        val result = repository.getChildren("album-1")

        assertEquals(2, result.size)
        assertEquals("track-1", result[0].mediaId)
        assertEquals("track-2", result[1].mediaId)
    }

    @Test
    fun `getChildren falls back to tree when cache empty`() = runTest {
        val treeItems = listOf(
            MediaItem.Builder()
                .setMediaId("track-1")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Track 1")
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                )
                .build()
        )

        coEvery { dao.getChildrenByParent("album-1") } returns emptyList()
        coEvery { tree.getChildren("album-1") } returns treeItems

        val result = repository.getChildren("album-1")

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
    }

    // --- getContentParentId tests ---

    @Test
    fun `getContentParentId returns first non-static parent ID`() = runTest {
        coEvery { dao.getParentIds("track-1") } returns listOf("album-1")

        val result = repository.getContentParentId("track-1")

        assertEquals("album-1", result)
    }

    @Test
    fun `getContentParentId filters out static IDs and returns null when only static parents exist`() = runTest {
        coEvery { dao.getParentIds("track-1") } returns listOf("LATEST_ALBUMS_ID", "ROOT_ID")

        val result = repository.getContentParentId("track-1")

        assertNull(result)
    }

    @Test
    fun `getContentParentId returns null when no parent IDs are found`() = runTest {
        coEvery { dao.getParentIds("track-1") } returns emptyList()

        val result = repository.getContentParentId("track-1")

        assertNull(result)
    }

    @Test
    fun `getContentParentId skips static IDs and returns the first real parent`() = runTest {
        coEvery { dao.getParentIds("track-1") } returns listOf("FAVOURITES_ID", "album-1")

        val result = repository.getContentParentId("track-1")

        assertEquals("album-1", result)
    }

    // --- getChildren for ROOT_ID tests ---

    @Test
    fun `getChildren for ROOT_ID always delegates directly to tree`() = runTest {
        val rootChildren = listOf(
            MediaItem.Builder()
                .setMediaId("LATEST_ALBUMS_ID")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Latest")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
        )
        coEvery { tree.getChildren("ROOT_ID") } returns rootChildren

        val result = repository.getChildren("ROOT_ID")

        assertEquals(1, result.size)
        assertEquals("LATEST_ALBUMS_ID", result[0].mediaId)
    }

    // --- getChildren network failure fallback test ---

    @Test
    fun `getChildren returns empty list when tree throws on network failure`() = runTest {
        coEvery { dao.getChildrenByParent("album-offline") } returns emptyList()
        coEvery { tree.getChildren("album-offline") } throws RuntimeException("Network unavailable")

        val result = repository.getChildren("album-offline")

        assertEquals(0, result.size)
    }

    // --- search delegate test ---

    @Test
    fun `search delegates to tree search`() = runTest {
        val treeResults = listOf(
            MediaItem.Builder()
                .setMediaId("track-1")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Search Result")
                        .build()
                )
                .build()
        )
        coEvery { tree.search("query") } returns treeResults

        val result = repository.search("query")

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
    }
}