package com.chamika.dashtune

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "PAGETEST"

/**
 * Reproduces the "browse list jumps back to the top while scrolling" report by asking the
 * session for children the way a paginating MediaBrowser does. AAOS head units that page
 * their browse lists expect page N to hold items [N*pageSize, (N+1)*pageSize).
 */
@RunWith(AndroidJUnit4::class)
class PaginatedBrowseTest {

    private val main = Handler(Looper.getMainLooper())

    /** Starts [block] on the main thread and hands back its future without blocking main. */
    private fun <T> startOnMain(block: () -> ListenableFuture<T>): ListenableFuture<T> {
        lateinit var future: ListenableFuture<T>
        val latch = CountDownLatch(1)
        main.post {
            future = block()
            latch.countDown()
        }
        check(latch.await(30, TimeUnit.SECONDS)) { "main thread never ran block" }
        return future
    }

    private fun childIds(browser: MediaBrowser, page: Int, pageSize: Int): List<String> =
        startOnMain { browser.getChildren(RANDOM_ALBUMS, page, pageSize, null) }
            .get(60, TimeUnit.SECONDS).value.orEmpty().map { it.mediaId }

    @Test
    fun pagedChildrenReturnDisjointSlices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = SessionToken(
            context,
            ComponentName(context, DashTuneMusicService::class.java)
        )

        val browser = startOnMain { MediaBrowser.Builder(context, token).buildAsync() }
            .get(30, TimeUnit.SECONDS)

        try {
            startOnMain { browser.getLibraryRoot(null) }.get(30, TimeUnit.SECONDS)

            val unpaged = childIds(browser, 0, Int.MAX_VALUE)
            val page0 = childIds(browser, 0, 20)
            val page1 = childIds(browser, 1, 20)
            val page2 = childIds(browser, 2, 20)

            Log.i(TAG, "unpaged size=${unpaged.size}")
            Log.i(TAG, "page0(size=20 requested) -> ${page0.size} items, first=${page0.firstOrNull()}")
            Log.i(TAG, "page1(size=20 requested) -> ${page1.size} items, first=${page1.firstOrNull()}")
            Log.i(TAG, "page2(size=20 requested) -> ${page2.size} items, first=${page2.firstOrNull()}")
            Log.i(TAG, "page0 == page1 ? ${page0 == page1}")
            Log.i(TAG, "page1 == page2 ? ${page1 == page2}")
            Log.i(TAG, "page0 overlaps page1 in ${page0.intersect(page1.toSet()).size} ids")
            Log.i(TAG, "unpaged == page0 ? ${unpaged == page0}")

            // Needs a signed-in server with a reasonably sized library; skip rather than
            // fail on a device that has neither.
            assumeTrue("no items to page over", unpaged.size > 40)
            assertEquals("page 0 must hold items 0..19", unpaged.take(20), page0)
            assertEquals("page 1 must hold items 20..39", unpaged.drop(20).take(20), page1)
            assertEquals("page 2 must hold items 40..59", unpaged.drop(40).take(20), page2)
        } finally {
            main.post { browser.release() }
        }
    }
}
