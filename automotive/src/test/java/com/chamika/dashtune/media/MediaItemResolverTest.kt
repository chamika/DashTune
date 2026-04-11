package com.chamika.dashtune.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.chamika.dashtune.data.MediaRepository
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaItemResolverTest {

    private lateinit var repository: MediaRepository
    private lateinit var resolver: MediaItemResolver

    @Before
    fun setUp() {
        repository = mockk()
        resolver = MediaItemResolver(repository)
    }

    private fun buildMediaItem(
        mediaId: String,
        mediaType: Int,
        isPlayable: Boolean,
        isBrowsable: Boolean,
        uri: String? = null,
        isAudiobook: Boolean = false,
        parentKey: String? = null
    ): MediaItem {
        val extras = Bundle()
        if (isAudiobook) extras.putBoolean(IS_AUDIOBOOK_KEY, true)
        if (parentKey != null) extras.putString(PARENT_KEY, parentKey)

        val metadata = MediaMetadata.Builder()
            .setTitle("Item $mediaId")
            .setMediaType(mediaType)
            .setIsPlayable(isPlayable)
            .setIsBrowsable(isBrowsable)
            .setExtras(extras)
            .build()

        val builder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)

        if (uri != null) {
            builder.setUri(uri)
        }

        return builder.build()
    }

    // --- resolveMediaItems tests ---

    @Test
    fun `playlist is expanded into child tracks`() = runTest {
        val playlist = buildMediaItem(
            mediaId = "playlist-1",
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
            isPlayable = true,
            isBrowsable = false
        )
        val track1 = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )
        val track2 = buildMediaItem(
            mediaId = "track-2",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-2"
        )

        coEvery { repository.getItem("playlist-1") } returns playlist
        coEvery { repository.getChildren("playlist-1") } returns listOf(track1, track2)
        coEvery { repository.getItem("track-1") } returns track1
        coEvery { repository.getItem("track-2") } returns track2

        val result = resolver.resolveMediaItems(listOf(playlist))

        assertEquals(2, result.size)
        assertEquals("track-1", result[0].mediaId)
        assertEquals("track-2", result[1].mediaId)
    }

    @Test
    fun `album is expanded into child tracks`() = runTest {
        val album = buildMediaItem(
            mediaId = "album-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = false
        )
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )

        coEvery { repository.getItem("album-1") } returns album
        coEvery { repository.getChildren("album-1") } returns listOf(track)
        coEvery { repository.getItem("track-1") } returns track

        val result = resolver.resolveMediaItems(listOf(album))

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
    }

    @Test
    fun `track is added directly without expansion`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )

        coEvery { repository.getItem("track-1") } returns track

        val result = resolver.resolveMediaItems(listOf(track))

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
    }

    @Test
    fun `single-file audiobook plays directly without expansion`() = runTest {
        val audiobook = buildMediaItem(
            mediaId = "book-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = false,
            isAudiobook = true,
            uri = "http://server/audio/book-1"
        )

        coEvery { repository.getItem("book-1") } returns audiobook

        val result = resolver.resolveMediaItems(listOf(audiobook))

        assertEquals(1, result.size)
        assertEquals("book-1", result[0].mediaId)
    }

    @Test
    fun `multi-chapter audiobook expands into chapters`() = runTest {
        val audiobook = buildMediaItem(
            mediaId = "book-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = true,
            isAudiobook = true,
            uri = "http://server/audio/book-1"
        )
        val chapter1 = buildMediaItem(
            mediaId = "chapter-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            isAudiobook = true,
            uri = "http://server/audio/chapter-1"
        )
        val chapter2 = buildMediaItem(
            mediaId = "chapter-2",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            isAudiobook = true,
            uri = "http://server/audio/chapter-2"
        )

        coEvery { repository.getItem("book-1") } returns audiobook
        coEvery { repository.getChildren("book-1") } returns listOf(chapter1, chapter2)
        coEvery { repository.getItem("chapter-1") } returns chapter1
        coEvery { repository.getItem("chapter-2") } returns chapter2

        val result = resolver.resolveMediaItems(listOf(audiobook))

        assertEquals(2, result.size)
        assertEquals("chapter-1", result[0].mediaId)
        assertEquals("chapter-2", result[1].mediaId)
    }

    @Test
    fun `mixed list of playlist and tracks resolves correctly`() = runTest {
        val track1 = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )
        val playlist = buildMediaItem(
            mediaId = "playlist-1",
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
            isPlayable = true,
            isBrowsable = false
        )
        val playlistTrack = buildMediaItem(
            mediaId = "track-2",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-2"
        )

        coEvery { repository.getItem("track-1") } returns track1
        coEvery { repository.getItem("playlist-1") } returns playlist
        coEvery { repository.getChildren("playlist-1") } returns listOf(playlistTrack)
        coEvery { repository.getItem("track-2") } returns playlistTrack

        val result = resolver.resolveMediaItems(listOf(track1, playlist))

        assertEquals(2, result.size)
        assertEquals("track-1", result[0].mediaId)
        assertEquals("track-2", result[1].mediaId)
    }

    @Test
    fun `non-playable non-expandable item is skipped`() = runTest {
        val folder = buildMediaItem(
            mediaId = "folder-1",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            isPlayable = false,
            isBrowsable = true
        )

        coEvery { repository.getItem("folder-1") } returns folder

        val result = resolver.resolveMediaItems(listOf(folder))

        assertEquals(0, result.size)
    }

    @Test
    fun `expandable item with empty children and URI plays directly`() = runTest {
        val audiobook = buildMediaItem(
            mediaId = "book-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = true,
            isAudiobook = true,
            uri = "http://server/audio/book-1"
        )

        coEvery { repository.getItem("book-1") } returns audiobook
        coEvery { repository.getChildren("book-1") } returns emptyList()

        val result = resolver.resolveMediaItems(listOf(audiobook))

        assertEquals(1, result.size)
        assertEquals("book-1", result[0].mediaId)
    }

    @Test
    fun `expandable item with empty children and no URI is skipped`() = runTest {
        val album = buildMediaItem(
            mediaId = "album-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = false
        )

        coEvery { repository.getItem("album-1") } returns album
        coEvery { repository.getChildren("album-1") } returns emptyList()

        val result = resolver.resolveMediaItems(listOf(album))

        assertEquals(0, result.size)
    }

    // --- isSingleItemWithParent tests ---

    @Test
    fun `isSingleItemWithParent returns true when item has PARENT_KEY`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            parentKey = "album-1"
        )

        coEvery { repository.getItem("track-1") } returns track

        assertTrue(resolver.isSingleItemWithParent(listOf(track)))
    }

    @Test
    fun `isSingleItemWithParent returns true when DB has parent`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false
        )

        coEvery { repository.getItem("track-1") } returns track
        coEvery { repository.getContentParentId("track-1") } returns "album-1"

        assertTrue(resolver.isSingleItemWithParent(listOf(track)))
    }

    @Test
    fun `isSingleItemWithParent returns false when no parent`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false
        )

        coEvery { repository.getItem("track-1") } returns track
        coEvery { repository.getContentParentId("track-1") } returns null

        assertFalse(resolver.isSingleItemWithParent(listOf(track)))
    }

    @Test
    fun `isSingleItemWithParent returns false for multiple items`() = runTest {
        val track1 = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            parentKey = "album-1"
        )
        val track2 = buildMediaItem(
            mediaId = "track-2",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            parentKey = "album-1"
        )

        assertFalse(resolver.isSingleItemWithParent(listOf(track1, track2)))
    }

    // --- expandSingleItem tests ---

    @Test
    fun `expandSingleItem returns siblings from parent`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-2",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            parentKey = "album-1"
        )
        val sibling1 = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )
        val sibling2 = buildMediaItem(
            mediaId = "track-2",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-2"
        )

        coEvery { repository.getItem("track-2") } returns track
        coEvery { repository.getChildren("album-1") } returns listOf(sibling1, sibling2)
        coEvery { repository.getItem("track-1") } returns sibling1

        val result = resolver.expandSingleItem(track)

        assertEquals(2, result.size)
        assertEquals("track-1", result[0].mediaId)
        assertEquals("track-2", result[1].mediaId)
    }

    @Test
    fun `expandSingleItem returns item itself when no parent found`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )

        coEvery { repository.getItem("track-1") } returns track
        coEvery { repository.getContentParentId("track-1") } returns null

        val result = resolver.expandSingleItem(track)

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
    }

    @Test
    fun `expandSingleItem uses DB parent when extras parent missing`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )
        val sibling = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )

        coEvery { repository.getItem("track-1") } returns track
        coEvery { repository.getContentParentId("track-1") } returns "album-1"
        coEvery { repository.getChildren("album-1") } returns listOf(sibling)

        val result = resolver.expandSingleItem(track)

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
    }
}
