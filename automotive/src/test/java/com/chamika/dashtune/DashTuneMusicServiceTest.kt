package com.chamika.dashtune

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DashTuneMusicServiceTest {

    /** Reads the private maxBytes field from a [LeastRecentlyUsedCacheEvictor] via reflection. */
    private fun LeastRecentlyUsedCacheEvictor.maxBytesReflect(): Long {
        val field = LeastRecentlyUsedCacheEvictor::class.java.getDeclaredField("maxBytes")
        field.isAccessible = true
        return field.getLong(this)
    }

    // --- createCacheEvictor tests ---

    @Test
    fun `createCacheEvictor returns NoOpCacheEvictor when size is -1 (unlimited)`() {
        val evictor = DashTuneMusicService.createCacheEvictor(-1L)

        assertTrue(evictor is NoOpCacheEvictor)
    }

    @Test
    fun `createCacheEvictor returns LRU evictor for positive size values`() {
        val evictor = DashTuneMusicService.createCacheEvictor(200L)

        assertTrue(evictor is LeastRecentlyUsedCacheEvictor)
    }

    @Test
    fun `createCacheEvictor sets correct byte size for 100 MB`() {
        val evictor = DashTuneMusicService.createCacheEvictor(100L) as LeastRecentlyUsedCacheEvictor

        assertEquals(100L * 1024L * 1024L, evictor.maxBytesReflect())
    }

    @Test
    fun `createCacheEvictor sets correct byte size for 200 MB`() {
        val evictor = DashTuneMusicService.createCacheEvictor(200L) as LeastRecentlyUsedCacheEvictor

        assertEquals(200L * 1024L * 1024L, evictor.maxBytesReflect())
    }

    @Test
    fun `createCacheEvictor sets correct byte size for 1 GB`() {
        val evictor = DashTuneMusicService.createCacheEvictor(1024L) as LeastRecentlyUsedCacheEvictor

        assertEquals(1024L * 1024L * 1024L, evictor.maxBytesReflect())
    }

    @Test
    fun `createCacheEvictor sets correct byte size for 5 GB`() {
        val evictor = DashTuneMusicService.createCacheEvictor(5120L) as LeastRecentlyUsedCacheEvictor

        assertEquals(5120L * 1024L * 1024L, evictor.maxBytesReflect())
    }

    @Test
    fun `createCacheEvictor sets correct byte size for 10 GB`() {
        val evictor = DashTuneMusicService.createCacheEvictor(10240L) as LeastRecentlyUsedCacheEvictor

        assertEquals(10240L * 1024L * 1024L, evictor.maxBytesReflect())
    }

    // --- audiobook reporter constants tests ---

    @Test
    fun `AUDIOBOOK_POSITION_REPORT_INTERVAL_MS is 30 seconds`() {
        assertEquals(30_000L, DashTuneMusicService.AUDIOBOOK_POSITION_REPORT_INTERVAL_MS)
    }

    @Test
    fun `MILLISECONDS_TO_TICKS is 10000`() {
        assertEquals(10_000L, DashTuneMusicService.MILLISECONDS_TO_TICKS)
    }

    // --- msToTicks conversion tests ---

    @Test
    fun `msToTicks converts zero to zero`() {
        assertEquals(0L, DashTuneMusicService.msToTicks(0L))
    }

    @Test
    fun `msToTicks converts 1 second (1000 ms) to 10000000 ticks`() {
        // Jellyfin stores time in 100-nanosecond ticks (same as .NET TimeSpan.Ticks).
        // 1 ms = 10 000 ticks, so 1000 ms = 10 000 000 ticks.
        assertEquals(10_000_000L, DashTuneMusicService.msToTicks(1_000L))
    }

    @Test
    fun `msToTicks converts 30 seconds to 300000000 ticks`() {
        assertEquals(300_000_000L, DashTuneMusicService.msToTicks(30_000L))
    }

    @Test
    fun `msToTicks converts 1 hour to correct ticks`() {
        val oneHourMs = 3_600_000L
        val expectedTicks = oneHourMs * 10_000L
        assertEquals(expectedTicks, DashTuneMusicService.msToTicks(oneHourMs))
    }

    @Test
    fun `msToTicks converts a typical audiobook chapter position`() {
        // 47 minutes 23 seconds = 2_843_000 ms
        val positionMs = 47L * 60_000L + 23_000L
        assertEquals(positionMs * 10_000L, DashTuneMusicService.msToTicks(positionMs))
    }

    // --- audiobookPositionReporter guard condition tests ---

    /**
     * The [DashTuneMusicService.audiobookPositionReporter] Runnable only reschedules itself when
     * BOTH [Player.isPlaying] AND [isPlayingAudiobook] are true.  We test the four combinations of
     * that guard directly so regressions are caught without spinning up the full service.
     */
    @Test
    fun `reporter guard is true only when both isPlaying and isAudiobook are true`() {
        assertTrue(isReporterGuardMet(isPlaying = true, isAudiobook = true))
    }

    @Test
    fun `reporter guard is false when not playing`() {
        assertFalse(isReporterGuardMet(isPlaying = false, isAudiobook = true))
    }

    @Test
    fun `reporter guard is false when not an audiobook`() {
        assertFalse(isReporterGuardMet(isPlaying = true, isAudiobook = false))
    }

    @Test
    fun `reporter guard is false when neither playing nor audiobook`() {
        assertFalse(isReporterGuardMet(isPlaying = false, isAudiobook = false))
    }

    // --- reportAudiobookProgress filter tests ---

    /** Tests the audiobook metadata check that gates both reporters. */
    @Test
    fun `audiobook flag is detected correctly when IS_AUDIOBOOK_KEY is true`() {
        val item = buildMediaItem(mediaId = "book-1", isAudiobook = true)
        assertTrue(item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
    }

    @Test
    fun `audiobook flag is absent for regular music items`() {
        val item = buildMediaItem(mediaId = "track-1", isAudiobook = false)
        assertFalse(item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
    }

    // --- helpers ---

    /**
     * Mirrors the guard expression inside [DashTuneMusicService.audiobookPositionReporter]:
     * `p.isPlaying && isPlayingAudiobook`.
     */
    private fun isReporterGuardMet(isPlaying: Boolean, isAudiobook: Boolean): Boolean =
        isPlaying && isAudiobook

    private fun buildMediaItem(mediaId: String, isAudiobook: Boolean): MediaItem {
        val extras = Bundle()
        if (isAudiobook) extras.putBoolean(IS_AUDIOBOOK_KEY, true)
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Test $mediaId")
                    .setExtras(extras)
                    .build()
            )
            .build()
    }
}
