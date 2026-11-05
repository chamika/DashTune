package com.chamika.dashtune

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.media.browse.MediaBrowser as PlatformMediaBrowser
import android.media.session.MediaController as PlatformMediaController
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.HeartRating
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chamika.dashtune.fake.ServerBehavior
import com.chamika.dashtune.support.DashTuneE2eRule
import com.chamika.dashtune.support.awaitCondition
import com.chamika.dashtune.support.childrenOf
import com.chamika.dashtune.support.onMain
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.TrackList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays real audio out of the simulated server through the real ExoPlayer instance the
 * service owns. The bytes are a generated WAV tone served with Range support, so this covers
 * the whole media path: media-id resolution in the session callback, the streaming URL the
 * MediaItemFactory builds, the Authorization header onLogin() installs on the data source,
 * and the play-state the service reports back.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackE2eTest {

    @get:Rule
    val dashTune = DashTuneE2eRule(foregroundForPlayback = true)

    private val browser get() = dashTune.browser
    private val fixture get() = dashTune.fixture

    private fun startAlbum() {
        val tracks = browser.childrenOf(fixture.featuredAlbum.id.toString())
        onMain {
            browser.setMediaItems(tracks)
            browser.prepare()
            browser.play()
        }
    }

    @Before
    fun clearStreamRequests() = dashTune.server.clearRequests()

    @Test
    fun favouriteChangesRefreshTheBrowseCategory() {
        val before = browser.childrenOf(FAVOURITES)
        val track = fixture.featuredTracks.first { !it.isFavorite }
        val id = track.id.toString()
        assertFalse(before.any { TrackList.trackId(it.mediaId) == id })
        val added = onMain { browser.setRating(id, HeartRating(true)) }.get(15, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, added.resultCode)
        assertTrue(dashTune.server.library.byId(track.id)?.isFavorite == true)
        assertTrue("The warm browse cache must include the newly liked track",
            browser.childrenOf(FAVOURITES).any { TrackList.trackId(it.mediaId) == id })

        val removed = onMain { browser.setRating(id, HeartRating(false)) }.get(15, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, removed.resultCode)
        assertFalse(dashTune.server.library.byId(track.id)?.isFavorite == true)
        assertFalse("The warm browse cache must remove the unliked track",
            browser.childrenOf(FAVOURITES).any { TrackList.trackId(it.mediaId) == id })
    }

    @Test
    fun anOlderFavouritesRefreshLandingLastDoesNotOverwriteANewerOne() {
        // #60: heart, hold that refresh's answer, unheart. The held answer (taken after the
        // heart, so it still lists the track) used to land last and put the track back.
        browser.childrenOf(FAVOURITES)
        val track = fixture.featuredTracks.first { !it.isFavorite }
        val id = track.id.toString()

        dashTune.server.holdNext(3_000) { url ->
            url.queryParameterValues("filters").any { it == "IsFavorite" }
        }
        val heart = onMain { browser.setRating(id, HeartRating(true)) }
        assertTrue("The heart's refresh never reached the server", dashTune.server.awaitHeldRequest())

        val unheart = onMain { browser.setRating(id, HeartRating(false)) }.get(15, TimeUnit.SECONDS)
        val hearted = heart.get(15, TimeUnit.SECONDS)

        assertEquals(SessionResult.RESULT_SUCCESS, hearted.resultCode)
        assertEquals(SessionResult.RESULT_SUCCESS, unheart.resultCode)
        assertFalse(dashTune.server.library.byId(track.id)?.isFavorite == true)
        assertFalse("The held, older favourites list must not overwrite the newer one",
            browser.childrenOf(FAVOURITES).any { it.mediaId == id })
    }

    @Test
    fun aSecondRatingWaitsForTheFirstToReachTheServer() {
        val track = fixture.featuredTracks.first { !it.isFavorite }
        val id = track.id.toString()

        dashTune.server.holdNext(2_000) { url -> url.encodedPath.startsWith("/UserFavoriteItems") }
        val heart = onMain { browser.setRating(id, HeartRating(true)) }
        assertTrue("The heart never reached the server", dashTune.server.awaitHeldRequest())
        val unheart = onMain { browser.setRating(id, HeartRating(false)) }
        Thread.sleep(1_000)

        assertEquals("The unheart must wait while the heart's answer is outstanding",
            listOf("POST"), dashTune.server.requestsTo("/UserFavoriteItems").map { it.method })

        assertEquals(SessionResult.RESULT_SUCCESS, heart.get(15, TimeUnit.SECONDS).resultCode)
        assertEquals(SessionResult.RESULT_SUCCESS, unheart.get(15, TimeUnit.SECONDS).resultCode)
        assertEquals(listOf("POST", "DELETE"),
            dashTune.server.requestsTo("/UserFavoriteItems").map { it.method })
        assertFalse(dashTune.server.library.byId(track.id)?.isFavorite == true)
    }

    /**
     * Starts the album and pauses on its first track. The fixture tone lasts 3s, so a test that
     * kept playing would soon be reading the next track's heart, not the one it rated.
     */
    private fun startAlbumPaused(): MediaItem {
        startAlbum()
        awaitCondition(message = { "player to report playing" }) { browser.isPlaying }
        onMain { browser.pause() }
        awaitCondition(message = { "player to pause" }) { !browser.isPlaying }
        return onMain { browser.currentMediaItem!! }
    }

    /** The rating the queue holds for [mediaId], wherever the player has got to. */
    private fun queuedRating(mediaId: String) = onMain {
        (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it) }
            .first { it.mediaId == mediaId }.mediaMetadata.userRating
    }

    @Test
    fun ratingAnotherTrackLeavesThePlayingTracksHeartAlone() {
        val playing = startAlbumPaused()
        val other = fixture.featuredTracks.first { it.id.toString() != playing.mediaId && !it.isFavorite }

        val result = onMain { browser.setRating(other.id.toString(), HeartRating(true)) }
            .get(15, TimeUnit.SECONDS)

        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(playing.mediaMetadata.userRating, queuedRating(playing.mediaId))
    }

    @Test
    fun aRatingTheServerRefusesPutsTheHeartBack() {
        val playing = startAlbumPaused()
        val before = playing.mediaMetadata.userRating
        val wanted = HeartRating(!((before as? HeartRating)?.isHeart ?: false))

        // Held, or the refusal could arrive before the browser has even seen the heart change,
        // and the rating would read as restored without anything having restored it.
        dashTune.server.holdNext(2_000) { url -> url.encodedPath.startsWith("/UserFavoriteItems") }
        dashTune.server.behavior = ServerBehavior.ServerError
        try {
            val rating = onMain { browser.setRating(playing.mediaId, wanted) }
            awaitCondition(message = { "the heart to change while the rating is in flight" }) {
                queuedRating(playing.mediaId) == wanted
            }
            assertNotEquals(SessionResult.RESULT_SUCCESS, rating.get(15, TimeUnit.SECONDS).resultCode)
        } finally {
            dashTune.server.behavior = ServerBehavior.Healthy
        }

        awaitCondition(timeoutMs = 5_000, message = { "the heart to be restored" }) {
            queuedRating(playing.mediaId) == before
        }
    }

    @Test
    fun playingAnAlbumReachesReadyAndPlays() {
        startAlbum()

        awaitCondition(message = { "player to become ready" }) {
            browser.playbackState == Player.STATE_READY
        }
        awaitCondition(message = { "player to report playing" }) { browser.isPlaying }

        assertEquals(
            fixture.featuredTracks.first().id.toString(),
            onMain { browser.currentMediaItem?.mediaId }
        )
        assertEquals(
            fixture.featuredTracks.first().name,
            onMain { browser.currentMediaItem?.mediaMetadata?.title }
        )

        // Position advancing proves the generated WAV was actually fetched and decoded,
        // not merely that the player accepted the item.
        awaitCondition(message = { "playback position to advance" }) {
            browser.currentPosition > 500
        }
    }

    @Test
    fun theStreamRequestUsesTheConfiguredContainersAndCarriesTheToken() {
        startAlbum()
        awaitCondition(message = { "player to become ready" }) {
            browser.playbackState == Player.STATE_READY
        }

        val trackId = fixture.featuredTracks.first().id
        val stream = dashTune.server.requestsTo("/Audio/$trackId/universal").firstOrNull()
            ?: throw AssertionError(
                "no stream request for $trackId; server saw ${dashTune.server.unhandledPaths()}"
            )

        assertEquals(
            "containers are sent as repeated keys, not a comma-joined value",
            listOf("flac", "mp3", "m4a", "aac", "ogg"),
            stream.queryValues("container")
        )
        assertEquals("mp3", stream.query("audioCodec"))
        assertEquals("mp3", stream.query("transcodingContainer"))
        assertTrue(
            "playback must carry the header onLogin() installs on the data source",
            stream.authorization?.contains("Token=\"${DashTuneE2eRule.TEST_TOKEN}\"") == true
        )
    }

    @Test
    fun playbackIsReportedToTheServer() {
        startAlbum()

        awaitCondition(message = { "a playback start report" }) {
            dashTune.server.requestsTo("/Sessions/Playing").isNotEmpty()
        }
    }

    @Test
    fun skipToNextAdvancesToTheFollowingTrack() {
        startAlbum()
        awaitCondition(message = { "player to become ready" }) {
            browser.playbackState == Player.STATE_READY
        }

        onMain { browser.seekToNextMediaItem() }

        awaitCondition(message = { "the second track to become current" }) {
            browser.currentMediaItem?.mediaId == fixture.featuredTracks[1].id.toString()
        }
        awaitCondition(message = { "the second track to become ready" }) {
            browser.playbackState == Player.STATE_READY
        }
    }

    @Test
    fun seekingIssuesARangeRequestAndKeepsPlaying() {
        startAlbum()
        awaitCondition(message = { "player to become ready" }) {
            browser.playbackState == Player.STATE_READY
        }

        onMain { browser.seekTo(1_500) }

        awaitCondition(message = { "playback to resume after the seek" }) {
            browser.playbackState == Player.STATE_READY && browser.currentPosition >= 1_400
        }
    }

    /**
     * The path Assistant takes for "Play X on DashTune": a platform MediaController calling
     * playFromSearch, which Media3 turns into one id-less item carrying the query.
     */
    @Test
    fun aVoiceRequestPlaysTheTrackItNamed() {
        val track = fixture.featuredTracks[1]
        val controller = platformController()

        onMain {
            controller.transportControls.playFromSearch(
                track.name,
                Bundle().apply {
                    putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                    putString(MediaStore.EXTRA_MEDIA_TITLE, track.name)
                }
            )
        }

        awaitCondition(message = { "the named track to become current" }) {
            browser.currentMediaItem?.mediaId == track.id.toString()
        }
        awaitCondition(message = { "player to report playing" }) { browser.isPlaying }
    }

    private var platformBrowser: PlatformMediaBrowser? = null

    @After
    fun disconnectPlatformBrowser() {
        platformBrowser?.let { onMain { it.disconnect() } }
    }

    private fun platformController(): PlatformMediaController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connected = CountDownLatch(1)
        val platform = onMain {
            PlatformMediaBrowser(
                context,
                ComponentName(context, DashTuneMusicService::class.java),
                object : PlatformMediaBrowser.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                },
                null
            ).also { it.connect() }
        }
        platformBrowser = platform
        assertTrue("platform MediaBrowser did not connect", connected.await(30, TimeUnit.SECONDS))
        return onMain { PlatformMediaController(context, platform.sessionToken) }
    }
}
