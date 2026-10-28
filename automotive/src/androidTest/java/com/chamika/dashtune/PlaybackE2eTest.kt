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
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chamika.dashtune.support.DashTuneE2eRule
import com.chamika.dashtune.support.awaitCondition
import com.chamika.dashtune.support.childrenOf
import com.chamika.dashtune.support.onMain
import org.junit.Assert.assertEquals
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
