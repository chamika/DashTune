package com.chamika.dashtune

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS
import com.chamika.dashtune.support.DashTuneE2eRule
import com.chamika.dashtune.support.childIdsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproduces the "browse list jumps back to the top while scrolling" report by asking the
 * session for children the way a paginating MediaBrowser does. AAOS head units that page
 * their browse lists expect page N to hold items [N*pageSize, (N+1)*pageSize).
 *
 * This used to require a real signed-in server with a large library and skipped itself
 * (`assumeTrue`) when it did not find one — which is to say, it guarded nothing on most
 * machines. The simulated server supplies a deterministic 72-album library instead, so the
 * assertions always run.
 */
@RunWith(AndroidJUnit4::class)
class PaginatedBrowseTest {

    @get:Rule
    val dashTune = DashTuneE2eRule(categories = setOf("latest", "random"))

    private val browser get() = dashTune.browser

    @Test
    fun pagedChildrenReturnDisjointSlices() {
        val unpaged = browser.childIdsOf(RANDOM_ALBUMS)

        assertEquals(
            "the fixture must supply more than three pages",
            dashTune.fixture.albums.size,
            unpaged.size
        )
        assertTrue("need more than 40 albums to page over", unpaged.size > 40)

        assertEquals("page 0 must hold items 0..19", unpaged.take(20), browser.childIdsOf(RANDOM_ALBUMS, 0, 20))
        assertEquals("page 1 must hold items 20..39", unpaged.drop(20).take(20), browser.childIdsOf(RANDOM_ALBUMS, 1, 20))
        assertEquals("page 2 must hold items 40..59", unpaged.drop(40).take(20), browser.childIdsOf(RANDOM_ALBUMS, 2, 20))
    }

    @Test
    fun theFinalPageIsTruncatedRatherThanWrapped() {
        val unpaged = browser.childIdsOf(RANDOM_ALBUMS)
        val lastFullPage = unpaged.size / 20

        assertEquals(
            "the trailing partial page holds only the remainder",
            unpaged.drop(lastFullPage * 20),
            browser.childIdsOf(RANDOM_ALBUMS, lastFullPage, 20)
        )
        assertTrue(
            "a page past the end must be empty, not a wrap-around",
            browser.childIdsOf(RANDOM_ALBUMS, lastFullPage + 5, 20).isEmpty()
        )
    }

    @Test
    fun pagesRemainStableAcrossRepeatedRequests() {
        // RANDOM_ALBUMS is sortBy=Random server-side; the repository caches one fetch so the
        // order must not reshuffle between pages, or a scrolling list would jump.
        assertEquals(browser.childIdsOf(RANDOM_ALBUMS, 1, 20), browser.childIdsOf(RANDOM_ALBUMS, 1, 20))
    }
}
