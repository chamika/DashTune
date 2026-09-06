package com.chamika.dashtune.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.chamika.dashtune.data.MediaRepository
import com.chamika.dashtune.media.MediaItemFactory.Companion.NEW_ALBUM_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.RADIO_ARTIST_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_LIBRARY
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_NEW
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Home tab's promise is that every tile plays for a long time. These cover the resolver
 * side of that: each synthetic tile expands into a real queue, and a recently added album
 * keeps playing past its own last track instead of leaving the car silent.
 */
@RunWith(RobolectricTestRunner::class)
class MediaItemResolverHomeTest {

    private lateinit var repository: MediaRepository
    private lateinit var resolver: MediaItemResolver

    @Before
    fun setUp() {
        repository = mockk()
        resolver = MediaItemResolver(repository)
    }

    private fun track(id: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Track $id")
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(Bundle())
                .build()
        )
        .setUri("https://example.invalid/$id")
        .build()

    private fun tile(id: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Tile")
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(Bundle())
                .build()
        )
        .build()

    @Test
    fun `an artist radio tile expands into the instant mix`() = runTest {
        val artistId = "artist-1"
        val mix = listOf(track("a"), track("b"), track("c"))
        coEvery { repository.getArtistRadioTracks(artistId) } returns mix

        val resolved = resolver.resolveMediaItems(listOf(tile(RADIO_ARTIST_PREFIX + artistId)))

        assertEquals(listOf("a", "b", "c"), resolved.map { it.mediaId })
    }

    @Test
    fun `shuffle favourites expands into the favourite tracks`() = runTest {
        coEvery { repository.getFavouriteTracksShuffled() } returns listOf(track("f1"), track("f2"))

        val resolved = resolver.resolveMediaItems(listOf(tile(SHUFFLE_FAVOURITES)))

        assertEquals(listOf("f1", "f2"), resolved.map { it.mediaId })
    }

    @Test
    fun `shuffle library expands into the library shuffle`() = runTest {
        coEvery { repository.getLibraryShuffled() } returns listOf(track("l1"), track("l2"))

        val resolved = resolver.resolveMediaItems(listOf(tile(SHUFFLE_LIBRARY)))

        assertEquals(listOf("l1", "l2"), resolved.map { it.mediaId })
    }

    @Test
    fun `shuffle new plays the newest tracks and asks for no more than the cap`() = runTest {
        val latest = (1..MediaItemResolver.LATEST_TRACKS_LIMIT).map { track("n$it") }
        coEvery {
            repository.getLatestTracks(MediaItemResolver.LATEST_TRACKS_LIMIT)
        } returns latest

        val resolved = resolver.resolveMediaItems(listOf(tile(SHUFFLE_NEW)))

        assertEquals(MediaItemResolver.LATEST_TRACKS_LIMIT, resolved.size)
        assertEquals(latest.map { it.mediaId }.toSet(), resolved.map { it.mediaId }.toSet())
        coVerify { repository.getLatestTracks(MediaItemResolver.LATEST_TRACKS_LIMIT) }
    }

    @Test
    fun `a new album plays its own tracks first, then keeps going`() = runTest {
        val albumId = "album-1"
        val albumTracks = listOf(track("t1"), track("t2"))
        coEvery { repository.getChildren(albumId) } returns albumTracks
        coEvery { repository.getItem("t1") } returns albumTracks[0]
        coEvery { repository.getItem("t2") } returns albumTracks[1]
        coEvery {
            repository.getLatestTracks(MediaItemResolver.LATEST_TRACKS_LIMIT)
        } returns listOf(track("t1"), track("t3"), track("t4"))

        val resolved = resolver.resolveMediaItems(listOf(tile(NEW_ALBUM_PREFIX + albumId)))

        // Album in order, then the rest of the newest tracks, with no track queued twice.
        assertEquals(listOf("t1", "t2", "t3", "t4"), resolved.map { it.mediaId })
    }

    @Test
    fun `a new album still plays when the continuation query comes back empty`() = runTest {
        val albumId = "album-1"
        val albumTracks = listOf(track("t1"), track("t2"))
        coEvery { repository.getChildren(albumId) } returns albumTracks
        coEvery { repository.getItem("t1") } returns albumTracks[0]
        coEvery { repository.getItem("t2") } returns albumTracks[1]
        coEvery { repository.getLatestTracks(any()) } returns emptyList()

        val resolved = resolver.resolveMediaItems(listOf(tile(NEW_ALBUM_PREFIX + albumId)))

        assertEquals(listOf("t1", "t2"), resolved.map { it.mediaId })
    }

    @Test
    fun `home tiles are never treated as one item inside a parent`() = runTest {
        val homeIds = listOf(
            MediaItemFactory.RESUME_QUEUE,
            SHUFFLE_FAVOURITES,
            SHUFFLE_NEW,
            SHUFFLE_LIBRARY,
            RADIO_ARTIST_PREFIX + "artist-1",
            NEW_ALBUM_PREFIX + "album-1"
        )

        homeIds.forEach { id ->
            assertFalse(id, resolver.isSingleItemWithParent(listOf(tile(id))))
        }

        // And no lookup was attempted for any of them — they have no server-side item.
        coVerify(exactly = 0) { repository.getItem(any()) }
        coVerify(exactly = 0) { repository.getContentParentId(any()) }
    }

    @Test
    fun `a failing radio query yields an empty queue rather than throwing`() = runTest {
        coEvery { repository.getArtistRadioTracks(any()) } returns emptyList()

        val resolved = resolver.resolveMediaItems(listOf(tile(RADIO_ARTIST_PREFIX + "artist-1")))

        assertTrue(resolved.isEmpty())
    }
}
