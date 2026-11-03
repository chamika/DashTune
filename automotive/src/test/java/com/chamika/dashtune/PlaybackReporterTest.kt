package com.chamika.dashtune

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.operations.PlayStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.UUID

/**
 * The server's play history and resume points come from these reports, so a Stopped that
 * cannot be sent in a dead zone is kept and sent later rather than lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackReporterTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var playState: PlayStateApi
    private lateinit var scope: TestScope
    private var transcoding = false
    private lateinit var reporter: PlaybackReporter

    private val trackA = UUID.randomUUID().toString()
    private val trackB = UUID.randomUUID().toString()
    private val ok = Response(Unit, 204, emptyMap())

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("reporter-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        playState = mockk()
        coEvery { playState.reportPlaybackStart(any()) } returns ok
        coEvery { playState.reportPlaybackProgress(any()) } returns ok
        coEvery { playState.reportPlaybackStopped(any()) } returns ok
        val api = mockk<ApiClient> { every { playStateApi } returns playState }
        scope = TestScope(UnconfinedTestDispatcher())
        reporter = PlaybackReporter(api, prefs, scope) { transcoding }
    }

    @Test
    fun `a track's session is started, then stopped at its position`() {
        reporter.started(trackA)
        reporter.stopped(180_000L)
        scope.runCurrent()

        val stop = slot<PlaybackStopInfo>()
        coVerify { playState.reportPlaybackStopped(capture(stop)) }
        assertEquals(trackA, stop.captured.itemId.toString())
        assertEquals(180_000L * 10_000L, stop.captured.positionTicks)
        assertNull(reporter.openItemId)
    }

    @Test
    fun `a transcoded stream is reported as a transcode`() {
        transcoding = true
        reporter.started(trackA)
        scope.runCurrent()

        val start = slot<PlaybackStartInfo>()
        coVerify { playState.reportPlaybackStart(capture(start)) }
        assertEquals(PlayMethod.TRANSCODE, start.captured.playMethod)
    }

    @Test
    fun `a pause is a paused progress report, and a stale item's progress is dropped`() {
        reporter.started(trackA)
        reporter.progress(trackA, 5_000L, isPaused = true)
        reporter.progress(trackB, 1_000L, isPaused = false)
        scope.runCurrent()

        val progress = slot<PlaybackProgressInfo>()
        coVerify(exactly = 1) { playState.reportPlaybackProgress(capture(progress)) }
        assertTrue(progress.captured.isPaused)
        assertEquals(trackA, progress.captured.itemId.toString())
    }

    @Test
    fun `stopping with nothing started sends nothing`() {
        reporter.stopped(1_000L)
        scope.runCurrent()

        coVerify(exactly = 0) { playState.reportPlaybackStopped(any()) }
    }

    @Test
    fun `a stop that fails offline is kept and sent when the network returns`() {
        coEvery { playState.reportPlaybackStopped(any()) } throws IOException("offline")
        reporter.started(trackA)
        reporter.stopped(60_000L)
        scope.runCurrent()
        assertTrue(prefs.contains(PlaybackReporter.PENDING_STOPS_PREF))

        coEvery { playState.reportPlaybackStopped(any()) } returns ok
        reporter.flushPending()
        scope.runCurrent()

        coVerify(exactly = 2) { playState.reportPlaybackStopped(match { it.itemId.toString() == trackA }) }
        assertFalse(prefs.contains(PlaybackReporter.PENDING_STOPS_PREF))
    }

    @Test
    fun `the next report that gets through sends the kept stops too`() {
        coEvery { playState.reportPlaybackStopped(any()) } throws IOException("offline")
        reporter.started(trackA)
        reporter.stopped(60_000L)
        scope.runCurrent()

        coEvery { playState.reportPlaybackStopped(any()) } returns ok
        reporter.started(trackB)
        scope.runCurrent()

        assertFalse(prefs.contains(PlaybackReporter.PENDING_STOPS_PREF))
    }

    @Test
    fun `kept stops are capped`() {
        coEvery { playState.reportPlaybackStopped(any()) } throws IOException("offline")
        coEvery { playState.reportPlaybackStart(any()) } throws IOException("offline")
        repeat(PlaybackReporter.MAX_PENDING_STOPS + 10) {
            reporter.started(UUID.randomUUID().toString())
            reporter.stopped(1_000L)
        }
        scope.runCurrent()

        val kept = prefs.getString(PlaybackReporter.PENDING_STOPS_PREF, "")!!.split(',').size
        assertEquals(PlaybackReporter.MAX_PENDING_STOPS, kept)
    }
}
