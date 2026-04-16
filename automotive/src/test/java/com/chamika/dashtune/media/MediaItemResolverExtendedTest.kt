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
class MediaItemResolverExtendedTest {

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

    // --- resolveMediaItems edge cases ---

    @Test
    fun `empty media items list returns empty result`() = runTest {
        val result = resolver.resolveMediaItems(emptyList())

        assertEquals(0, result.size)
    }

    @Test
    fun `multiple tracks are all added directly`() = runTest {
        val tracks = (1..5).map { i ->
            buildMediaItem(
                mediaId = "track-$i",
                mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
                isPlayable = true,
                isBrowsable = false,
                uri = "http://server/audio/track-$i"
            )
        }

        tracks.forEach { track ->
            coEvery { repository.getItem(track.mediaId) } returns track
        }

        val result = resolver.resolveMediaItems(tracks)

        assertEquals(5, result.size)
        result.forEachIndexed { index, item ->
            assertEquals("track-${index + 1}", item.mediaId)
        }
    }

    @Test
    fun `nested album expansion resolves recursively`() = runTest {
        // Album contains another album-type item (e.g., disc), which contains tracks
        val album = buildMediaItem(
            mediaId = "album-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
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

        coEvery { repository.getItem("album-1") } returns album
        coEvery { repository.getChildren("album-1") } returns listOf(track1, track2)
        coEvery { repository.getItem("track-1") } returns track1
        coEvery { repository.getItem("track-2") } returns track2

        val result = resolver.resolveMediaItems(listOf(album))

        assertEquals(2, result.size)
    }

    @Test
    fun `audiobook without children and without URI is skipped`() = runTest {
        // browsable audiobook with no children and no URI
        val audiobook = buildMediaItem(
            mediaId = "book-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = true,
            isAudiobook = true
            // No URI
        )

        coEvery { repository.getItem("book-1") } returns audiobook
        coEvery { repository.getChildren("book-1") } returns emptyList()

        val result = resolver.resolveMediaItems(listOf(audiobook))

        assertEquals(0, result.size)
    }

    @Test
    fun `non-browsable audiobook with URI plays directly`() = runTest {
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
    fun `playlist with single track resolves to that track`() = runTest {
        val playlist = buildMediaItem(
            mediaId = "playlist-1",
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
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

        coEvery { repository.getItem("playlist-1") } returns playlist
        coEvery { repository.getChildren("playlist-1") } returns listOf(track)
        coEvery { repository.getItem("track-1") } returns track

        val result = resolver.resolveMediaItems(listOf(playlist))

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
    }

    @Test
    fun `multiple playlists are all expanded`() = runTest {
        val playlist1 = buildMediaItem(
            mediaId = "playlist-1",
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
            isPlayable = true,
            isBrowsable = false
        )
        val playlist2 = buildMediaItem(
            mediaId = "playlist-2",
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

        coEvery { repository.getItem("playlist-1") } returns playlist1
        coEvery { repository.getItem("playlist-2") } returns playlist2
        coEvery { repository.getChildren("playlist-1") } returns listOf(track1)
        coEvery { repository.getChildren("playlist-2") } returns listOf(track2)
        coEvery { repository.getItem("track-1") } returns track1
        coEvery { repository.getItem("track-2") } returns track2

        val result = resolver.resolveMediaItems(listOf(playlist1, playlist2))

        assertEquals(2, result.size)
        assertEquals("track-1", result[0].mediaId)
        assertEquals("track-2", result[1].mediaId)
    }

    // --- isSingleItemWithParent edge cases ---

    @Test
    fun `isSingleItemWithParent returns false for empty list`() = runTest {
        assertFalse(resolver.isSingleItemWithParent(emptyList()))
    }

    @Test
    fun `isSingleItemWithParent returns true when item has PARENT_KEY in extras`() = runTest {
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
    fun `isSingleItemWithParent returns false for three items`() = runTest {
        val tracks = (1..3).map { i ->
            buildMediaItem(
                mediaId = "track-$i",
                mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
                isPlayable = true,
                isBrowsable = false,
                parentKey = "album-1"
            )
        }

        assertFalse(resolver.isSingleItemWithParent(tracks))
    }

    // --- expandSingleItem edge cases ---

    @Test
    fun `expandSingleItem with parent in extras gets siblings`() = runTest {
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
        val sibling3 = buildMediaItem(
            mediaId = "track-3",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-3"
        )

        coEvery { repository.getItem("track-2") } returns track
        coEvery { repository.getChildren("album-1") } returns listOf(sibling1, sibling2, sibling3)
        coEvery { repository.getItem("track-1") } returns sibling1
        coEvery { repository.getItem("track-3") } returns sibling3

        val result = resolver.expandSingleItem(track)

        assertEquals(3, result.size)
        assertEquals("track-1", result[0].mediaId)
        assertEquals("track-2", result[1].mediaId)
        assertEquals("track-3", result[2].mediaId)
    }

    @Test
    fun `expandSingleItem with empty parent children returns item itself`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1",
            parentKey = "album-1"
        )

        coEvery { repository.getItem("track-1") } returns track
        coEvery { repository.getChildren("album-1") } returns emptyList()

        val result = resolver.expandSingleItem(track)

        // Empty children from album: no playable tracks, so empty result
        assertEquals(0, result.size)
    }
}
