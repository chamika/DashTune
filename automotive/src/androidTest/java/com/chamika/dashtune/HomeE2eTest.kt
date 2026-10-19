package com.chamika.dashtune

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_IDS_PREF
import com.chamika.dashtune.DashTuneSessionCallback.Companion.SYNC_COMMAND
import com.chamika.dashtune.data.MediaRepository.Companion.SAVED_QUEUE
import com.chamika.dashtune.media.HomeLayout
import com.chamika.dashtune.media.MediaItemFactory.Companion.HOME
import com.chamika.dashtune.media.MediaItemFactory.Companion.NEW_ALBUM_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RADIO_ARTIST_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.RESUME_QUEUE
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_LIBRARY
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_NEW
import com.chamika.dashtune.media.MediaItemResolver
import com.chamika.dashtune.support.DashTuneE2eRule
import com.chamika.dashtune.support.awaitCondition
import com.chamika.dashtune.support.awaitOnMain
import com.chamika.dashtune.support.childTitlesOf
import com.chamika.dashtune.support.childIdsOf
import com.chamika.dashtune.support.childrenOf
import com.chamika.dashtune.support.onMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Home tab end to end, through a real MediaBrowser against a simulated Jellyfin server.
 *
 * Two things are worth proving here that unit tests cannot: that Home's five sections
 * survive a round trip through the Media3 browse bridge with their group titles intact, and
 * that every tile really does expand into a queue of more than one track. A tile that plays
 * a single song would defeat the point of the tab.
 */
@RunWith(AndroidJUnit4::class)
class HomeE2eTest {

    @get:Rule
    val dashTune = DashTuneE2eRule(categories = setOf("home", "favourites", "books", "playlists"))

    private val browser get() = dashTune.browser
    private val fixture get() = dashTune.fixture

    private val tilesPerRow get() = HomeLayout.tilesPerRow(dashTune.context)

    private fun home(): List<MediaItem> = browser.childrenOf(HOME)

    private fun MediaItem.group(): String? = mediaMetadata.extras
        ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE)

    private fun section(name: Int): List<MediaItem> {
        val title = dashTune.context.getString(name)
        return home().filter { it.group() == title }
    }

    /**
     * Sets one tile and waits for the session callback to swap it for the real queue.
     *
     * Media3 puts the requested item into the controller's queue straight away and replaces
     * it when onSetMediaItems resolves, so waiting merely for a non-empty queue would read
     * the tile back and call it a one-track queue.
     */
    private fun setAndResolve(item: MediaItem): List<String> {
        onMain {
            browser.setMediaItems(listOf(item))
            browser.prepare()
        }
        awaitCondition(message = { "${item.mediaId} to resolve into a queue" }) {
            onMain {
                browser.mediaItemCount > 0 &&
                    browser.getMediaItemAt(0).mediaId != item.mediaId
            }
        }
        return onMain {
            (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it).mediaId }
        }
    }

    // --- Structure ---

    @Test
    fun homeIsAmongTheRootTabs() {
        assertTrue(browser.childIdsOf(ROOT_ID).contains(HOME))
    }

    @Test
    fun homeSectionsAppearInTheDesignedOrder() {
        val groups = home().map { it.group() }.distinct()

        // "Continue listening" is present without this test playing anything, because the
        // fixture library ships a part-read audiobook — exactly the state a driver returns
        // to after leaving a book half finished.
        assertEquals(
            listOf(
                dashTune.context.getString(R.string.continue_listening),
                dashTune.context.getString(R.string.made_for_you),
                dashTune.context.getString(R.string.new_for_you),
                dashTune.context.getString(R.string.jump_back_in),
                dashTune.context.getString(R.string.mixes)
            ),
            groups
        )
    }

    @Test
    fun noSectionIsWiderThanOneRow() {
        val counts = home().groupingBy { it.group() }.eachCount()

        assertTrue(counts.isNotEmpty())
        counts.forEach { (group, count) ->
            assertTrue("$group had $count tiles, row fits $tilesPerRow", count <= tilesPerRow)
        }
    }

    @Test
    fun everySectionEndsWithItsShuffleAction() {
        val ids = home().map { it.mediaId }

        assertTrue(ids.contains(SHUFFLE_FAVOURITES))
        assertTrue(ids.contains(SHUFFLE_NEW))
        assertTrue(ids.contains(SHUFFLE_LIBRARY))
    }

    @Test
    fun everyHomeTileIsPlayableRatherThanBrowsable() {
        home().forEach {
            assertTrue("${it.mediaId} not playable", it.mediaMetadata.isPlayable == true)
            assertFalse("${it.mediaId} is browsable", it.mediaMetadata.isBrowsable == true)
        }
    }

    @Test
    fun jumpBackInShowsTheAlbumsOfRecentlyPlayedTracksNewestFirst() {
        val titles = section(R.string.jump_back_in).map { it.mediaMetadata.title.toString() }

        assertEquals(fixture.historyAlbums.take(tilesPerRow).map { it.name }, titles)
    }

    @Test
    fun buildingHomeHitsNoUnmodelledRoute() {
        home()

        // A route this server does not model answers 501, which the app turns into an empty
        // section rather than an error — so without this check a whole section could quietly
        // disappear and every other assertion here would still pass.
        assertEquals(emptyList<String>(), dashTune.server.unhandledRequests)
    }

    // --- Queues ---

    @Test
    fun shuffleNewPlaysTheNewestTracksAndNoMoreThanTheCap() {
        val tile = home().first { it.mediaId == SHUFFLE_NEW }

        val queue = setAndResolve(tile)

        assertTrue("queue was ${queue.size}", queue.size > 1)
        assertTrue("queue was ${queue.size}", queue.size <= MediaItemResolver.LATEST_TRACKS_LIMIT)
    }

    @Test
    fun aNewAlbumPlaysItsOwnTracksFirstThenKeepsGoing() {
        val tile = section(R.string.new_for_you).first {
            it.mediaId.startsWith(NEW_ALBUM_PREFIX)
        }
        val albumId = tile.mediaId.removePrefix(NEW_ALBUM_PREFIX)
        val albumTrackIds = fixture.tracks
            .filter { it.parentId.toString() == albumId }
            .map { it.id.toString() }

        val queue = setAndResolve(tile)

        assertEquals(albumTrackIds, queue.take(albumTrackIds.size))
        assertTrue(
            "queue of ${queue.size} did not continue past the album",
            queue.size > albumTrackIds.size
        )
        assertEquals("queue contained a duplicate", queue.size, queue.distinct().size)
    }

    @Test
    fun anArtistRadioPlaysAMultiTrackQueue() {
        val tile = home().first { it.mediaId.startsWith(RADIO_ARTIST_PREFIX) }

        val queue = setAndResolve(tile)

        assertTrue("queue was ${queue.size}", queue.size > 1)
    }

    @Test
    fun shuffleLibraryPlaysAMultiTrackQueue() {
        val tile = home().first { it.mediaId == SHUFFLE_LIBRARY }

        val queue = setAndResolve(tile)

        assertTrue("queue was ${queue.size}", queue.size > 1)
    }

    @Test
    fun shuffleFavouritesPlaysTheFavouriteTracks() {
        val tile = home().first { it.mediaId == SHUFFLE_FAVOURITES }

        val queue = setAndResolve(tile)

        assertTrue(queue.contains(fixture.favouriteTrack.id.toString()))
    }

    // --- Continue listening ---

    /**
     * Queues the featured album and waits for the session to persist it, returning its ids.
     *
     * The wait is on the saved-queue preference rather than on the controller's queue: the
     * controller shows the requested items immediately, so a queue-length check would return
     * before onSetMediaItems had written anything for Home to read.
     */
    private fun playFeaturedAlbum(): List<String> {
        val album = browser.childrenOf(fixture.featuredAlbum.id.toString())
        onMain {
            browser.setMediaItems(album)
            browser.prepare()
        }
        awaitCondition(message = { "the queue to be persisted" }) {
            !savedQueueIds().isNullOrEmpty()
        }
        return album.map { it.mediaId }
    }

    /** Instrumentation shares the app's process, so the saved queue is readable directly. */
    private fun savedQueueIds(): String? =
        PreferenceManager.getDefaultSharedPreferences(dashTune.context)
            .getString(PLAYLIST_IDS_PREF, null)

    @Test
    fun playingSomethingAddsTheResumeRowToTheTopOfHome() {
        // Nothing to resume on a fresh install.
        assertFalse(home().any { it.mediaId == RESUME_QUEUE })

        playFeaturedAlbum()

        val refreshed = home()

        assertEquals(RESUME_QUEUE, refreshed.first().mediaId)
        assertEquals(
            dashTune.context.getString(R.string.continue_listening),
            refreshed.first().group()
        )
    }

    @Test
    fun theResumeTileReplaysTheSavedQueue() {
        // Resuming used to drop every track that was not downloaded unless the network was
        // validated — which a car rarely is just after starting, and a headless emulator
        // never is. It no longer filters, so this holds on any network.
        val albumIds = playFeaturedAlbum()

        val tile = home().first { it.mediaId == RESUME_QUEUE }
        val queue = setAndResolve(tile)

        assertEquals(albumIds, queue)
    }

    @Test
    fun playbackResumesFromTheSavedQueueWithTheServerGone() {
        val albumIds = playFeaturedAlbum()
        awaitCondition(message = { "the queue's tracks to be saved for a cold start" }) {
            dashTune.cachedChildIds(SAVED_QUEUE) == albumIds
        }
        onMain {
            browser.stop()
            browser.clearMediaItems()
        }

        dashTune.server.close()
        // Re-running login drops the in-memory tree cache, as a cold start would.
        dashTune.applyLogin()

        // What the head unit sends at start-up: play() on an empty player.
        onMain { browser.play() }
        awaitCondition(message = { "the saved queue to be restored" }) {
            onMain { browser.mediaItemCount } == albumIds.size
        }
        assertEquals(
            albumIds,
            onMain { (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it).mediaId } }
        )
    }

    // --- Offline cache ---

    @Test
    fun aSyncWithTheServerGoneLeavesTheOfflineCacheAlone() {
        // The field failure: with Home among the tabs, a sync at the start of a drive — before
        // the server was reachable — counted Home's offline tiles as success and replaced the
        // whole cache with nothing.
        val playlists = browser.childTitlesOf(PLAYLISTS)
        assertTrue("expected playlists while online", playlists.isNotEmpty())

        dashTune.server.close()
        val result = awaitOnMain {
            browser.sendCustomCommand(SessionCommand(SYNC_COMMAND, Bundle()), Bundle())
        }

        assertNotEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(playlists, browser.childTitlesOf(PLAYLISTS))
    }
}
