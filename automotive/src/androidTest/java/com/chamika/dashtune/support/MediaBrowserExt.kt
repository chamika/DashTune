package com.chamika.dashtune.support

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Main-thread plumbing for driving a [MediaBrowser] from an instrumentation thread.
 *
 * Every `MediaController` call must be made on the application main thread, but the test
 * thread must not *block* the main thread while the service answers — that would deadlock,
 * since the session callbacks resolve on the same looper.  So each helper posts the call,
 * hands back the future, and waits on the calling thread.
 */
private val main = Handler(Looper.getMainLooper())

/** Runs [block] on the main thread and returns its result. */
fun <T> onMain(timeoutSeconds: Long = 30, block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()

    val result = AtomicReference<Result<T>>()
    val latch = CountDownLatch(1)
    main.post {
        result.set(runCatching(block))
        latch.countDown()
    }
    check(latch.await(timeoutSeconds, TimeUnit.SECONDS)) { "main thread never ran block" }
    return result.get().getOrThrow()
}

/** Starts a future-returning call on the main thread and awaits it on this thread. */
fun <T> awaitOnMain(timeoutSeconds: Long = 60, block: () -> ListenableFuture<T>): T =
    onMain { block() }.get(timeoutSeconds, TimeUnit.SECONDS)

fun MediaBrowser.libraryRoot(): MediaItem =
    awaitOnMain { getLibraryRoot(null) }.value!!

fun MediaBrowser.childrenOf(parentId: String, page: Int = 0, pageSize: Int = Int.MAX_VALUE): List<MediaItem> =
    awaitOnMain { getChildren(parentId, page, pageSize, null) }.value.orEmpty()

fun MediaBrowser.childIdsOf(parentId: String, page: Int = 0, pageSize: Int = Int.MAX_VALUE): List<String> =
    childrenOf(parentId, page, pageSize).map { it.mediaId }

fun MediaBrowser.childTitlesOf(parentId: String): List<String> =
    childrenOf(parentId).map { it.mediaMetadata.title?.toString().orEmpty() }

fun MediaBrowser.item(mediaId: String): MediaItem =
    awaitOnMain { getItem(mediaId) }.value!!

/** The raw result, for assertions about failures rather than about content. */
fun MediaBrowser.childrenResultCode(parentId: String): Int =
    awaitOnMain { getChildren(parentId, 0, Int.MAX_VALUE, null) }.resultCode

/**
 * Polls [condition] on the main thread until it holds. Used instead of a fixed sleep so
 * playback assertions do not race ExoPlayer's asynchronous state transitions.
 */
fun awaitCondition(
    timeoutMs: Long = 30_000,
    pollMs: Long = 100,
    message: () -> String,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (onMain { condition() }) return
        Thread.sleep(pollMs)
    }
    throw AssertionError("Timed out after ${timeoutMs}ms waiting for: ${message()}")
}
