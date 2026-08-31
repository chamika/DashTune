package com.chamika.dashtune

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chamika.dashtune.fake.ServerBehavior
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.chamika.dashtune.support.DashTuneE2eRule
import com.chamika.dashtune.support.childIdsOf
import com.chamika.dashtune.support.childTitlesOf
import com.chamika.dashtune.support.childrenResultCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A browse call that never returns is the worst failure this app has: the Media3 command
 * queue is single-threaded per session, so one wedged callback freezes browsing and playback
 * for good (issue #31). These tests drive each server fault through the real session and
 * assert two things every time — the call comes back within a bounded time, and the session
 * still works afterwards.
 */
@RunWith(AndroidJUnit4::class)
class FailureModeE2eTest {

    @get:Rule
    val dashTune = DashTuneE2eRule(categories = setOf("latest", "random"))

    private val browser get() = dashTune.browser
    private val fixture get() = dashTune.fixture

    /** Browses under [behavior] and returns how long the session took to answer. */
    private fun browseUnder(behavior: ServerBehavior): Long {
        dashTune.server.behavior = behavior
        val startedAt = System.currentTimeMillis()
        browser.childrenResultCode(LATEST_ALBUMS)
        return System.currentTimeMillis() - startedAt
    }

    /** The session must still serve real content once the server is healthy again. */
    private fun assertSessionRecovers() {
        dashTune.server.behavior = ServerBehavior.Healthy
        dashTune.clearBrowseCache()

        assertEquals(
            "the session must still answer after a failure",
            fixture.albums.map { it.name },
            browser.childTitlesOf(LATEST_ALBUMS)
        )
    }

    @Test
    fun aServerErrorFailsFastAndLeavesTheSessionUsable() {
        val elapsed = browseUnder(ServerBehavior.ServerError)

        assertTrue("500 should fail fast, took ${elapsed}ms", elapsed < 8_000)
        assertSessionRecovers()
    }

    @Test
    fun anExpiredTokenFailsFastAndLeavesTheSessionUsable() {
        val elapsed = browseUnder(ServerBehavior.Unauthorized)

        assertTrue("401 should fail fast, took ${elapsed}ms", elapsed < 8_000)
        assertSessionRecovers()
    }

    @Test
    fun malformedJsonDoesNotWedgeTheSession() {
        val elapsed = browseUnder(ServerBehavior.MalformedJson)

        assertTrue("a decode failure should fail fast, took ${elapsed}ms", elapsed < 8_000)
        assertSessionRecovers()
    }

    @Test
    fun aHangingServerIsBoundedByTheBrowseTimeout() {
        // The app's HTTP request timeout is 7s and the session's browse guard is 8s, so a
        // server that accepts the connection and then stalls must still answer well inside
        // the retry ladder's worst case (10s + 1s + 10s + 2s).
        val elapsed = browseUnder(ServerBehavior.Hang(delayMillis = 30_000))

        assertTrue("a hung server must not exceed the browse timeout, took ${elapsed}ms", elapsed < 12_000)
        assertSessionRecovers()
    }

    @Test
    fun aRefusedConnectionFailsFastAndLeavesTheSessionUsable() {
        dashTune.server.close()

        val startedAt = System.currentTimeMillis()
        browser.childrenResultCode(LATEST_ALBUMS)
        val elapsed = System.currentTimeMillis() - startedAt

        // Connection-refused is retried three times with 1s and 2s backoff, so it should
        // land around 3s and well inside the 8s browse guard.
        assertTrue("a refused connection should fail fast, took ${elapsed}ms", elapsed < 8_000)

        // The root is served from the tree without touching the network, so browsing must
        // keep working even with no server at all.
        assertEquals(2, browser.childIdsOf(ROOT_ID).size)
    }

    @Test
    fun cachedChildrenStayBrowsableAfterTheServerDisappears() {
        val online = browser.childTitlesOf(LATEST_ALBUMS)
        assertTrue("expected content while online", online.isNotEmpty())

        dashTune.server.close()

        assertEquals(
            "Room-cached children must still browse with the server gone",
            online,
            browser.childTitlesOf(LATEST_ALBUMS)
        )
    }

    @Test
    fun theSessionStillAcceptsCommandsAfterAFailedBrowse() {
        browseUnder(ServerBehavior.Hang(delayMillis = 30_000))
        dashTune.server.behavior = ServerBehavior.Healthy

        // applyLogin() goes through the same custom-command channel a wedged session would
        // block, and times out rather than returning if the queue is stuck.
        dashTune.applyLogin()
        assertTrue("browsing must work again", browser.childIdsOf(ROOT_ID).isNotEmpty())
    }
}
