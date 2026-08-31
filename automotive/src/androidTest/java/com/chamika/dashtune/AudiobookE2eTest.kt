package com.chamika.dashtune

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chamika.dashtune.fake.RESUME_POSITION_MS
import com.chamika.dashtune.fake.TICKS_PER_MS
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.support.DashTuneE2eRule
import com.chamika.dashtune.support.awaitCondition
import com.chamika.dashtune.support.childTitlesOf
import com.chamika.dashtune.support.childrenOf
import com.chamika.dashtune.support.onMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the audiobook path end to end: the Books library lookup through `/UserViews`, the
 * collection -> book -> chapter hierarchy, the AAOS completion extras that draw progress bars
 * on chapter rows, expansion of a book into an ordered chapter queue, and the position
 * round-trip against the server's UserData API.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookE2eTest {

    @get:Rule
    val dashTune = DashTuneE2eRule(foregroundForPlayback = true)

    private val browser get() = dashTune.browser
    private val fixture get() = dashTune.fixture

    private val completionStatus = "android.media.extra.COMPLETION_STATUS"
    private val completionPercentage = "android.media.extra.COMPLETION_PERCENTAGE"

    private fun chapterItems() = browser.childrenOf(fixture.multiChapterBook.id.toString())

    @Test
    fun booksCategoryResolvesTheBooksLibraryAndListsItsContents() {
        assertEquals(
            listOf(fixture.standaloneBook.name, fixture.bookCollection.name),
            browser.childTitlesOf(BOOKS)
        )
        assertTrue(
            "the Books category must be resolved through /UserViews",
            dashTune.server.requestsTo("/UserViews").isNotEmpty()
        )
    }

    @Test
    fun collectionsDrillDownToBooksThenChapters() {
        assertEquals(
            listOf(fixture.multiChapterBook.name),
            browser.childTitlesOf(fixture.bookCollection.id.toString())
        )
        assertEquals(fixture.chapters.map { it.name }, chapterItems().map { it.mediaMetadata.title })
    }

    @Test
    fun everyBookItemIsFlaggedAsAudiobook() {
        val book = browser.childrenOf(fixture.bookCollection.id.toString()).single()

        assertTrue(
            "a book with chapters is both browsable and playable",
            book.mediaMetadata.isBrowsable == true && book.mediaMetadata.isPlayable == true
        )
        assertTrue(
            "chapters must carry the audiobook flag so shuffle stays disabled",
            chapterItems().all { it.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true }
        )
    }

    @Test
    fun chaptersCarryCompletionExtrasForTheBrowseUi() {
        val extras = chapterItems().map { it.mediaMetadata.extras!! }

        // 2 = fully played, 1 = partially played, 0 = not played.
        assertEquals(
            listOf(2, 1, 0),
            extras.map { it.getInt(completionStatus) }
        )
        assertEquals(1.0, extras[0].getDouble(completionPercentage), 0.001)
        assertEquals(
            "percentage is reported 0..1, from the server's 0..100 PlayedPercentage",
            0.4,
            extras[1].getDouble(completionPercentage),
            0.001
        )
    }

    @Test
    fun playingABookExpandsItIntoItsChaptersInOrder() {
        val book = browser.childrenOf(fixture.bookCollection.id.toString()).single()
        onMain {
            browser.setMediaItems(listOf(book))
            browser.prepare()
            browser.play()
        }

        awaitCondition(message = { "the book to expand into its chapters" }) {
            browser.mediaItemCount == fixture.chapters.size
        }
        assertEquals(
            fixture.chapters.map { it.id.toString() },
            onMain { (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it).mediaId } }
        )
    }

    @Test
    fun playingAChapterResumesFromThePositionHeldOnTheServer() {
        val partiallyPlayed = chapterItems()[1]
        onMain {
            browser.setMediaItems(listOf(partiallyPlayed))
            browser.prepare()
            browser.play()
        }

        awaitCondition(message = { "the chapter queue to be built" }) {
            browser.mediaItemCount == fixture.chapters.size
        }

        assertEquals(
            "selecting a chapter keeps its siblings and starts on that chapter",
            1,
            onMain { browser.currentMediaItemIndex }
        )
        awaitCondition(message = { "playback to start at the saved server position" }) {
            browser.currentPosition >= RESUME_POSITION_MS - 100
        }
    }

    @Test
    fun pausingAChapterPersistsThePositionBackToTheServer() {
        val chapter = chapterItems()[2]
        onMain {
            browser.setMediaItems(listOf(chapter))
            browser.prepare()
            browser.play()
        }

        awaitCondition(message = { "the chapter to start playing" }) { browser.isPlaying }
        // The service samples the position from a 1s poll rather than reading the player at
        // pause time, so pausing sooner than that would legitimately persist 0.
        awaitCondition(message = { "at least one position poll to land" }) {
            browser.currentPosition > 1_500
        }

        onMain { browser.pause() }

        val path = "/UserItems/${chapter.mediaId}/UserData"
        awaitCondition(message = { "a UserData write for $path" }) {
            dashTune.server.requestsTo(path).isNotEmpty()
        }

        val body = dashTune.server.requestsTo(path).last().body
        val ticks = Regex("\"PlaybackPositionTicks\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLong()
            ?: throw AssertionError("no PlaybackPositionTicks in $body")
        assertTrue(
            "the saved position should be where playback actually got to, was ${ticks / TICKS_PER_MS}ms",
            ticks / TICKS_PER_MS > 500
        )
    }

    @Test
    fun aSingleFileBookPlaysItselfRatherThanExpanding() {
        val standalone = browser.childrenOf(BOOKS).first { it.mediaId == fixture.standaloneBook.id.toString() }
        onMain {
            browser.setMediaItems(listOf(standalone))
            browser.prepare()
            browser.play()
        }

        awaitCondition(message = { "the standalone book to become ready" }) {
            browser.playbackState == Player.STATE_READY
        }
        assertEquals(1, onMain { browser.mediaItemCount })
        assertEquals(
            fixture.standaloneBook.id.toString(),
            onMain { browser.currentMediaItem?.mediaId }
        )
    }
}
