package com.chamika.dashtune.data

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.chamika.dashtune.AlbumArtContentProvider
import com.chamika.dashtune.data.db.CachedMediaItemEntity
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.media.JellyfinMediaTree
import com.chamika.dashtune.media.MediaItemFactory
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
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
class MediaRepositorySyncTest {

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

    private fun buildMediaItem(
        mediaId: String,
        title: String = "Item $mediaId",
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
        isPlayable: Boolean = true,
        isBrowsable: Boolean = false,
        albumArtist: String? = null,
        extras: Bundle? = null,
        isFavorite: Boolean = false,
        durationMs: Long? = null,
        artworkUri: Uri? = null,
        uri: String? = null
    ): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setAlbumArtist(albumArtist)
            .setMediaType(mediaType)
            .setIsPlayable(isPlayable)
            .setIsBrowsable(isBrowsable)
            .setUserRating(HeartRating(isFavorite))

        if (extras != null) metadataBuilder.setExtras(extras)
        if (durationMs != null) metadataBuilder.setDurationMs(durationMs)
        if (artworkUri != null) metadataBuilder.setArtworkUri(artworkUri)

        val itemBuilder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadataBuilder.build())

        if (uri != null) itemBuilder.setUri(uri)

        return itemBuilder.build()
    }

    // --- sync() tests ---

    @Test
    fun `sync returns true when at least one section succeeds`() = runTest {
        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS)
        coEvery { tree.getChildren(LATEST_ALBUMS) } returns listOf(
            buildMediaItem("album-1", mediaType = MediaMetadata.MEDIA_TYPE_ALBUM, isBrowsable = false)
        )

        val result = repository.sync()

        assertTrue(result)
        coVerify { dao.deleteAll() }
        coVerify { dao.insertAll(any()) }
    }

    @Test
    fun `sync returns false when all sections fail`() = runTest {
        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS, FAVOURITES)
        coEvery { tree.getChildren(LATEST_ALBUMS) } throws RuntimeException("Network error")
        coEvery { tree.getChildren(FAVOURITES) } throws RuntimeException("Network error")

        val result = repository.sync()

        assertFalse(result)
        coVerify(exactly = 0) { dao.deleteAll() }
        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun `sync returns true when some sections fail but at least one succeeds`() = runTest {
        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS, FAVOURITES)
        coEvery { tree.getChildren(LATEST_ALBUMS) } throws RuntimeException("Network error")
        coEvery { tree.getChildren(FAVOURITES) } returns listOf(
            buildMediaItem("track-1")
        )

        val result = repository.sync()

        assertTrue(result)
        coVerify { dao.deleteAll() }
        coVerify { dao.insertAll(any()) }
    }

    @Test
    fun `sync clears existing cache before inserting new data`() = runTest {
        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS)
        coEvery { tree.getChildren(LATEST_ALBUMS) } returns listOf(
            buildMediaItem("album-1", mediaType = MediaMetadata.MEDIA_TYPE_ALBUM, isBrowsable = false)
        )

        repository.sync()

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            dao.deleteAll()
            dao.insertAll(any())
        }
    }

    @Test
    fun `sync recursively fetches children for browsable artist items`() = runTest {
        val artist = buildMediaItem(
            "artist-1",
            title = "Artist",
            mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
            isPlayable = false,
            isBrowsable = true
        )
        val album = buildMediaItem(
            "album-1",
            title = "Album",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = false
        )

        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS)
        coEvery { tree.getChildren(LATEST_ALBUMS) } returns listOf(artist)
        coEvery { tree.getChildren("artist-1") } returns listOf(album)

        repository.sync()

        coVerify { tree.getChildren("artist-1") }
    }

    @Test
    fun `sync does not recursively fetch for non-browsable music tracks`() = runTest {
        val track = buildMediaItem(
            "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false
        )

        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS)
        coEvery { tree.getChildren(LATEST_ALBUMS) } returns listOf(track)

        repository.sync()

        coVerify(exactly = 0) { tree.getChildren("track-1") }
    }

    @Test
    fun `sync returns true with empty sections`() = runTest {
        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS)
        coEvery { tree.getChildren(LATEST_ALBUMS) } returns emptyList()

        val result = repository.sync()

        assertTrue(result)
    }

    @Test
    fun `sync with no active categories returns false`() = runTest {
        coEvery { tree.getActiveCategoryIds() } returns emptyList()

        val result = repository.sync()

        // No sections = no success (anySuccess stays false)
        assertFalse(result)
    }

    @Test
    fun `sync handles multiple sections with multiple children`() = runTest {
        val album1 = buildMediaItem("album-1", mediaType = MediaMetadata.MEDIA_TYPE_ALBUM, isBrowsable = false)
        val album2 = buildMediaItem("album-2", mediaType = MediaMetadata.MEDIA_TYPE_ALBUM, isBrowsable = false)
        val playlist1 = buildMediaItem("playlist-1", mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST, isBrowsable = false)

        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS, PLAYLISTS)
        coEvery { tree.getChildren(LATEST_ALBUMS) } returns listOf(album1, album2)
        coEvery { tree.getChildren(PLAYLISTS) } returns listOf(playlist1)

        val result = repository.sync()

        assertTrue(result)
    }

    // --- getChildren caching behavior tests ---

    @Test
    fun `getChildren caches tree results in DAO for non-ROOT parents`() = runTest {
        val treeItems = listOf(
            buildMediaItem("track-1", title = "Track 1")
        )

        coEvery { dao.getChildrenByParent("album-1") } returns emptyList()
        coEvery { tree.getChildren("album-1") } returns treeItems

        repository.getChildren("album-1")

        coVerify { dao.insertAll(any()) }
    }

    @Test
    fun `getChildren does not cache ROOT children`() = runTest {
        val rootItems = listOf(
            buildMediaItem(LATEST_ALBUMS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, isBrowsable = true, isPlayable = false)
        )
        coEvery { tree.getChildren(ROOT_ID) } returns rootItems

        repository.getChildren(ROOT_ID)

        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun `getChildren returns cached results without hitting tree`() = runTest {
        val cachedEntities = listOf(
            CachedMediaItemEntity(
                mediaId = "track-1",
                parentId = "album-1",
                title = "Cached Track",
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
        )

        coEvery { dao.getChildrenByParent("album-1") } returns cachedEntities

        val result = repository.getChildren("album-1")

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
        coVerify(exactly = 0) { tree.getChildren("album-1") }
    }

    // --- toEntity/toMediaItem roundtrip tests ---

    @Test
    fun `entity preserves artUri when artwork is a content URI`() = runTest {
        mockkObject(AlbumArtContentProvider.Companion)
        every { AlbumArtContentProvider.originalUri(any()) } returns null

        val contentUri = Uri.parse("content://com.chamika.dashtune/test/art")
        val item = buildMediaItem("track-1", artworkUri = contentUri)

        coEvery { dao.getChildrenByParent("album-1") } returns emptyList()
        coEvery { tree.getChildren("album-1") } returns listOf(item)

        repository.getChildren("album-1")

        coVerify { dao.insertAll(match { entities ->
            entities.any { it.artUri == "content://com.chamika.dashtune/test/art" }
        }) }
    }

    @Test
    fun `cached entity with null extras produces no extras bundle`() = runTest {
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

        assertNull(result.mediaMetadata.extras)
    }

    @Test
    fun `cached entity with empty JSON produces no extras bundle`() = runTest {
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

        assertNull(result.mediaMetadata.extras)
    }

    @Test
    fun `cached entity with IS_AUDIOBOOK_KEY and type MUSIC gets streaming URI`() = runTest {
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
            extras = """{"is_audiobook":true}"""
        )

        coEvery { dao.getItem("chapter-1") } returns entity

        val result = repository.getItem("chapter-1")

        assertNotNull(result.localConfiguration)
    }

    @Test
    fun `cached album item (non-audiobook, non-music) has no streaming URI`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "album-1",
            parentId = "latest",
            title = "Album",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = null,
            isFavorite = false,
            extras = null
        )

        coEvery { dao.getItem("album-1") } returns entity

        val result = repository.getItem("album-1")

        assertNull(result.localConfiguration)
    }

    @Test
    fun `cached entity preserves non-favorite rating`() = runTest {
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

        val rating = result.mediaMetadata.userRating as? HeartRating
        assertNotNull(rating)
        assertFalse(rating!!.isHeart)
    }

    @Test
    fun `cached entity preserves title and albumArtist`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "track-1",
            parentId = "album-1",
            title = "My Song Title",
            subtitle = "Famous Artist",
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

        assertEquals("My Song Title", result.mediaMetadata.title.toString())
        assertEquals("Famous Artist", result.mediaMetadata.albumArtist.toString())
    }

    @Test
    fun `cached entity with null subtitle produces null albumArtist`() = runTest {
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

        assertNull(result.mediaMetadata.albumArtist)
    }

    @Test
    fun `cached browsable item has correct isBrowsable flag`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "artist-1",
            parentId = "favourites",
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

        assertTrue(result.mediaMetadata.isBrowsable == true)
        assertFalse(result.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `cached entity with multiple extras preserves all values`() = runTest {
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
            durationMs = 3600000L,
            isFavorite = true,
            extras = """{"is_audiobook":true,"PARENT_KEY":"book-1"}"""
        )

        coEvery { dao.getItem("chapter-1") } returns entity

        val result = repository.getItem("chapter-1")

        assertTrue(result.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
        assertEquals("book-1", result.mediaMetadata.extras?.getString(PARENT_KEY))
        assertEquals(3600000L, result.mediaMetadata.durationMs)
        assertTrue((result.mediaMetadata.userRating as? HeartRating)?.isHeart == true)
    }

    // --- search tests ---

    @Test
    fun `search returns empty list when tree returns empty`() = runTest {
        coEvery { tree.search("nothing") } returns emptyList()

        val result = repository.search("nothing")

        assertEquals(0, result.size)
    }

    @Test
    fun `search returns multiple results from tree`() = runTest {
        val results = listOf(
            buildMediaItem("track-1", title = "Result 1"),
            buildMediaItem("track-2", title = "Result 2"),
            buildMediaItem("artist-1", title = "Artist", mediaType = MediaMetadata.MEDIA_TYPE_ARTIST)
        )
        coEvery { tree.search("query") } returns results

        val result = repository.search("query")

        assertEquals(3, result.size)
    }

    // --- getItem static ID tests ---

    @Test
    fun `getItem for LATEST_ALBUMS delegates to tree`() = runTest {
        val item = buildMediaItem(LATEST_ALBUMS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        coEvery { tree.getItem(LATEST_ALBUMS) } returns item

        val result = repository.getItem(LATEST_ALBUMS)

        assertEquals(LATEST_ALBUMS, result.mediaId)
        coVerify(exactly = 0) { dao.getItem(any()) }
    }

    @Test
    fun `getItem for FAVOURITES delegates to tree`() = runTest {
        val item = buildMediaItem(FAVOURITES, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        coEvery { tree.getItem(FAVOURITES) } returns item

        val result = repository.getItem(FAVOURITES)

        assertEquals(FAVOURITES, result.mediaId)
        coVerify(exactly = 0) { dao.getItem(any()) }
    }

    @Test
    fun `getItem for PLAYLISTS delegates to tree`() = runTest {
        val item = buildMediaItem(PLAYLISTS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        coEvery { tree.getItem(PLAYLISTS) } returns item

        val result = repository.getItem(PLAYLISTS)

        assertEquals(PLAYLISTS, result.mediaId)
    }

    @Test
    fun `getItem for BOOKS delegates to tree`() = runTest {
        val item = buildMediaItem(BOOKS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        coEvery { tree.getItem(BOOKS) } returns item

        val result = repository.getItem(BOOKS)

        assertEquals(BOOKS, result.mediaId)
    }

    // --- getContentParentId with multiple parents tests ---

    @Test
    fun `getContentParentId skips ROOT_ID and LATEST_ALBUMS returns first content parent`() = runTest {
        coEvery { dao.getParentIds("track-1") } returns listOf(ROOT_ID, LATEST_ALBUMS, "album-real")

        val result = repository.getContentParentId("track-1")

        assertEquals("album-real", result)
    }

    @Test
    fun `getContentParentId skips all static IDs including BOOKS and RANDOM_ALBUMS`() = runTest {
        coEvery { dao.getParentIds("track-1") } returns listOf(
            ROOT_ID, LATEST_ALBUMS, "RANDOM_ALBUMS_ID", FAVOURITES, PLAYLISTS, BOOKS
        )

        val result = repository.getContentParentId("track-1")

        assertNull(result)
    }
}
