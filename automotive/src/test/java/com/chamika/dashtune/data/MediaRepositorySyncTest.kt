package com.chamika.dashtune.data

import kotlinx.coroutines.runBlocking
import android.os.Looper
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.chamika.dashtune.AlbumArtContentProvider
import com.chamika.dashtune.data.MediaRepository.Companion.SAVED_QUEUE
import com.chamika.dashtune.data.db.CachedMediaItemEntity
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.media.JellyfinMediaTree
import com.chamika.dashtune.media.MediaItemFactory
import com.chamika.dashtune.media.MediaItemFactory.Companion.ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ARTISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.FOLDERS
import com.chamika.dashtune.media.MediaItemFactory.Companion.GENRES
import com.chamika.dashtune.media.MediaItemFactory.Companion.HOME
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_FOLDER_PREFIX
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.UnknownHostException

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
    fun `refreshing favourites replaces only that parent with the current server list`() = runTest {
        val track = buildMediaItem("new-favourite", isFavorite = true)
        coEvery { tree.getChildren(FAVOURITES) } returns listOf(track)

        assertEquals(listOf("new-favourite"), repository.refreshFavourites().map { it.mediaId })

        coVerify(exactly = 1) {
            dao.replaceChildren(match { parents ->
                parents.keys == setOf(FAVOURITES) &&
                    parents.getValue(FAVOURITES).single().mediaId == "new-favourite"
            })
        }
        coVerify(exactly = 0) { dao.replaceAllExcept(any(), any()) }
        coVerify(exactly = 0) { dao.deleteAll() }
    }

    @Test
    fun `removing the last favourite replaces its cached children with an empty list`() = runTest {
        coEvery { tree.getChildren(FAVOURITES) } returns emptyList()

        assertTrue(repository.refreshFavourites().isEmpty())

        coVerify(exactly = 1) { dao.replaceChildren(mapOf(FAVOURITES to emptyList())) }
    }

    @Test
    fun `a failed favourite refresh keeps the previous offline list`() = runTest {
        val saved = CachedMediaItemEntity(
            mediaId = "saved-favourite", parentId = FAVOURITES, title = "Saved favourite",
            subtitle = null, artUri = null, mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true, isBrowsable = false, sortOrder = 0, durationMs = null,
            isFavorite = true, extras = null
        )
        coEvery { dao.getChildrenByParent(FAVOURITES) } returns listOf(saved)
        coEvery { tree.getChildren(FAVOURITES) } throws UnknownHostException("offline")

        assertEquals(listOf("saved-favourite"), repository.refreshFavourites().map { it.mediaId })

        verifyNoCacheWrites()
    }

    /** Every sync write goes through one of the two transactional replaces; nothing else may touch the cache. */
    private fun verifyNoCacheWrites() {
        coVerify(exactly = 0) { dao.replaceAllExcept(any(), any()) }
        coVerify(exactly = 0) { dao.replaceChildren(any()) }
        coVerify(exactly = 0) { dao.deleteAll() }
        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun `a sync in which every section answers replaces the whole cache except Home and the saved queue`() = runTest {
        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS)
        coEvery { tree.getChildren(LATEST_ALBUMS) } returns listOf(
            buildMediaItem("album-1", mediaType = MediaMetadata.MEDIA_TYPE_ALBUM, isBrowsable = false)
        )
        val written = slot<List<CachedMediaItemEntity>>()

        val result = repository.sync()

        assertTrue(result)
        coVerify { dao.replaceAllExcept(capture(written), listOf(HOME, SAVED_QUEUE)) }
        assertEquals(listOf("album-1" to LATEST_ALBUMS), written.captured.map { it.mediaId to it.parentId })
        coVerify(exactly = 0) { dao.deleteAll() }
        coVerify(exactly = 0) { dao.replaceChildren(any()) }
    }

    @Test
    fun `sync returns false and leaves the cache alone when all sections fail`() = runTest {
        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS, FAVOURITES)
        coEvery { tree.getChildren(LATEST_ALBUMS) } throws RuntimeException("Network error")
        coEvery { tree.getChildren(FAVOURITES) } throws RuntimeException("Network error")

        val result = repository.sync()

        assertFalse(result)
        verifyNoCacheWrites()
    }

    @Test
    fun `a partly failed sync replaces only what it re-fetched`() = runTest {
        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS, FAVOURITES)
        coEvery { tree.getChildren(LATEST_ALBUMS) } throws RuntimeException("Network error")
        coEvery { tree.getChildren(FAVOURITES) } returns listOf(
            buildMediaItem("track-1")
        )
        val written = slot<Map<String, List<CachedMediaItemEntity>>>()

        val result = repository.sync()

        assertTrue(result)
        coVerify { dao.replaceChildren(capture(written)) }
        // LATEST_ALBUMS keeps whatever it had: it is not among the replaced parents.
        assertEquals(setOf(FAVOURITES), written.captured.keys)
        coVerify(exactly = 0) { dao.replaceAllExcept(any(), any()) }
        coVerify(exactly = 0) { dao.deleteAll() }
    }

    @Test
    fun `Home and the alphabet indexes are not synced and cannot pass for a successful sync`() = runTest {
        // The field failure: with the server unreachable every Home section timed out, Home
        // came back empty without throwing, and that counted as success — so the sync
        // replaced the entire cache with nothing.
        coEvery { tree.getActiveCategoryIds() } returns listOf(HOME, ARTISTS, ALBUMS, FAVOURITES)
        coEvery { tree.getChildren(FAVOURITES) } throws
            TimeoutException("HTTP host unreachable", UnknownHostException("server"))

        val result = repository.sync()

        assertFalse(result)
        verifyNoCacheWrites()
        coVerify(exactly = 0) { tree.getChildren(HOME) }
        coVerify(exactly = 0) { tree.getChildren(ARTISTS) }
        coVerify(exactly = 0) { tree.getChildren(ALBUMS) }
    }

    @Test
    fun `a failed child fetch keeps the sync partial so that child keeps its cached rows`() = runTest {
        val artist = buildMediaItem(
            "artist-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
            isPlayable = false,
            isBrowsable = true
        )
        coEvery { tree.getActiveCategoryIds() } returns listOf(FAVOURITES)
        coEvery { tree.getChildren(FAVOURITES) } returns listOf(artist)
        coEvery { tree.getChildren("artist-1") } throws RuntimeException("Network error")
        val written = slot<Map<String, List<CachedMediaItemEntity>>>()

        assertTrue(repository.sync())

        coVerify { dao.replaceChildren(capture(written)) }
        assertEquals(setOf(FAVOURITES), written.captured.keys)
        coVerify(exactly = 0) { dao.replaceAllExcept(any(), any()) }
    }

    @Test
    fun `playlist tracks are synced although playlists are not browsable`() = runTest {
        val playlist = buildMediaItem(
            "playlist-1",
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
            isBrowsable = false
        )
        coEvery { tree.getActiveCategoryIds() } returns listOf(PLAYLISTS)
        coEvery { tree.getChildren(PLAYLISTS) } returns listOf(playlist)
        coEvery { tree.getChildren("playlist-1") } returns listOf(buildMediaItem("track-1"))
        val written = slot<List<CachedMediaItemEntity>>()

        assertTrue(repository.sync())

        coVerify { dao.replaceAllExcept(capture(written), any()) }
        assertTrue(written.captured.any { it.mediaId == "track-1" && it.parentId == "playlist-1" })
    }

    @Test
    fun `shuffle pseudo-items are never expanded during sync`() = runTest {
        val shuffle = buildMediaItem(
            SHUFFLE_FOLDER_PREFIX + "folder-1",
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
            isBrowsable = false
        )
        coEvery { tree.getActiveCategoryIds() } returns listOf(FAVOURITES)
        coEvery { tree.getChildren(FAVOURITES) } returns listOf(shuffle)

        assertTrue(repository.sync())

        coVerify(exactly = 0) { tree.getChildren(shuffle.mediaId) }
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
    fun `sync does not recursively fetch children for the folders section`() = runTest {
        val folder = buildMediaItem(
            "folder-1",
            title = "Folder",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            isPlayable = false,
            isBrowsable = true
        )

        coEvery { tree.getActiveCategoryIds() } returns listOf(FOLDERS)
        coEvery { tree.getChildren(FOLDERS) } returns listOf(folder)

        repository.sync()

        coVerify(exactly = 0) { tree.getChildren("folder-1") }
    }

    @Test
    fun `sync does not recursively fetch children for the alphabet index or genres`() = runTest {
        // Recursing would fan out to 27 buckets x every artist x every album — a
        // library-sized fetch for what is meant to be a routine sync.
        val bucket = buildMediaItem(
            MediaItemFactory.letterBucketId(ARTISTS, "A"),
            title = "A",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            isPlayable = false,
            isBrowsable = true
        )
        val genre = buildMediaItem(
            "genre-1",
            title = "Rock",
            mediaType = MediaMetadata.MEDIA_TYPE_GENRE,
            isPlayable = false,
            isBrowsable = true
        )

        coEvery { tree.getActiveCategoryIds() } returns listOf(ARTISTS, ALBUMS, GENRES)
        coEvery { tree.getChildren(ARTISTS) } returns listOf(bucket)
        coEvery { tree.getChildren(ALBUMS) } returns listOf(bucket)
        coEvery { tree.getChildren(GENRES) } returns listOf(genre)

        assertTrue(repository.sync())

        coVerify(exactly = 0) { tree.getChildren(bucket.mediaId) }
        coVerify(exactly = 0) { tree.getChildren("genre-1") }
    }

    @Test
    fun `a section the server answered with nothing still counts as synced`() = runTest {
        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS)
        coEvery { tree.getChildren(LATEST_ALBUMS) } returns emptyList()

        val result = repository.sync()

        assertTrue(result)
        coVerify { dao.replaceAllExcept(emptyList(), listOf(HOME, SAVED_QUEUE)) }
    }

    @Test
    fun `sync with no active categories returns false`() = runTest {
        coEvery { tree.getActiveCategoryIds() } returns emptyList()

        val result = repository.sync()

        // No sections = nothing reached the server
        assertFalse(result)
        verifyNoCacheWrites()
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

        coVerify { dao.replaceChildren(match { it.keys == setOf("album-1") }) }
    }

    @Test
    fun `getChildren does not cache ROOT children`() = runTest {
        val rootItems = listOf(
            buildMediaItem(LATEST_ALBUMS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, isBrowsable = true, isPlayable = false)
        )
        coEvery { tree.getChildren(ROOT_ID) } returns rootItems

        repository.getChildren(ROOT_ID)

        coVerify(exactly = 0) { dao.insertAll(any()) }
        coVerify(exactly = 0) { dao.replaceChildren(any()) }
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

        coVerify { dao.replaceChildren(match { children ->
            children["album-1"].orEmpty().any { it.artUri == "content://com.chamika.dashtune/test/art" }
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

    @Test
    fun `getItem for FOLDERS delegates to tree`() = runTest {
        val item = buildMediaItem(FOLDERS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        coEvery { tree.getItem(FOLDERS) } returns item

        val result = repository.getItem(FOLDERS)

        assertEquals(FOLDERS, result.mediaId)
        coVerify(exactly = 0) { dao.getItem(any()) }
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

    // --- Threading ---

    @Test
    fun `a sync started on the main thread fetches off it`() {
        // The service starts the network-triggered sync from its main-thread scope, and the
        // SDK reads each response body on the thread that resumes it: on main, a large
        // library failed with NetworkOnMainThreadException.
        val main = Looper.getMainLooper()
        assertEquals("Robolectric runs tests on the main looper", main, Looper.myLooper())
        var fetchedOnMain: Boolean? = null
        coEvery { tree.getActiveCategoryIds() } returns listOf(LATEST_ALBUMS)
        coEvery { tree.getChildren(LATEST_ALBUMS) } answers {
            fetchedOnMain = Looper.myLooper() == main
            emptyList()
        }

        runBlocking { repository.sync() }

        assertEquals(false, fetchedOnMain)
    }
}
