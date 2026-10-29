package com.chamika.dashtune

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_TRACKS
import com.chamika.dashtune.media.TrackList
import com.chamika.dashtune.support.DashTuneE2eRule
import com.chamika.dashtune.support.awaitCondition
import com.chamika.dashtune.support.childrenOf
import com.chamika.dashtune.support.onMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackListE2eTest {
    @get:Rule
    val dashTune = DashTuneE2eRule(categories = setOf("random", "favourites"), foregroundForPlayback = true)

    private val browser get() = dashTune.browser
    private val fixture get() = dashTune.fixture

    @Test
    fun randomTrackKeepsTheDisplayedQueueWhenSkipping() {
        val tracks = browser.childrenOf(RANDOM_TRACKS)
        assertTrue(tracks.size > 40)
        assertTrue(tracks.all(TrackList::isSong))
        assertEquals(tracks.map { it.mediaId }, browser.childrenOf(RANDOM_TRACKS).map { it.mediaId })
        assertRows(browser.childrenOf(RANDOM_TRACKS))

        val selected = tracks[7]
        val song = fixture.tracks.first { it.id.toString() == TrackList.trackId(selected.mediaId) }
        // Warm the same song under its album too, so an ID-only lookup is ambiguous.
        browser.childrenOf(song.albumId.toString())
        selectTrack(tracks, 7)
        onMain { browser.seekToNextMediaItem() }
        awaitCondition(message = { "next song from the Random list" }) {
            browser.currentMediaItem?.mediaId == TrackList.trackId(tracks[8].mediaId)
        }
    }

    @Test
    fun favouriteTrackKeepsOnlyFavouriteSongsWhenItFinishes() {
        val library = dashTune.server.library
        library.replace(fixture.favouriteTrack.copy(isFavorite = false))
        val songs = listOf(fixture.tracks[0], fixture.tracks[6], fixture.tracks[10])
        songs.forEach { library.replace(it.copy(isFavorite = true)) }
        browser.childrenOf(songs[1].albumId.toString())
        val favourites = browser.childrenOf(FAVOURITES)
        // Favourite containers remain available, but must not add non-favourite songs.
        assertTrue(favourites.any { it.mediaId == fixture.favouriteAlbum.id.toString() })
        assertTrue(favourites.any { it.mediaId == fixture.favouriteArtist.id.toString() })
        val tracks = favourites.filter(TrackList::isSong)
        assertEquals(songs.map { it.id.toString() }, tracks.map { TrackList.trackId(it.mediaId) })
        assertRows(browser.childrenOf(FAVOURITES).filter(TrackList::isSong))

        selectTrack(tracks, 1)
        onMain {
            browser.seekTo(2_750)
            browser.play()
        }
        awaitCondition(message = { "automatic advance to the next favourite song" }) {
            browser.currentMediaItem?.mediaId == songs[2].id.toString()
        }
    }

    private fun selectTrack(tracks: List<MediaItem>, selectedIndex: Int) {
        // Car hosts can discard the browse metadata and send only a media ID.
        onMain {
            browser.setMediaItem(MediaItem.Builder().setMediaId(tracks[selectedIndex].mediaId).build())
            browser.prepare()
        }
        awaitCondition(message = { "the selected track and its list queue" }) {
            browser.mediaItemCount == tracks.size &&
                browser.currentMediaItem?.mediaId == TrackList.trackId(tracks[selectedIndex].mediaId)
        }
        assertEquals(selectedIndex, onMain { browser.currentMediaItemIndex })
        assertEquals(tracks.map { TrackList.trackId(it.mediaId) }, onMain {
            (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it).mediaId }
        })
        awaitCondition(message = { "selected track ready" }) { browser.playbackState == Player.STATE_READY }
    }

    private fun assertRows(tracks: List<MediaItem>) {
        tracks.forEach { item ->
            val metadata = item.mediaMetadata
            assertTrue(!metadata.title.isNullOrBlank())
            assertTrue(!metadata.subtitle.isNullOrBlank())
            assertEquals(metadata.artist, metadata.subtitle)
            assertNotNull(metadata.artworkUri)
            assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                metadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM))
        }
    }
}
