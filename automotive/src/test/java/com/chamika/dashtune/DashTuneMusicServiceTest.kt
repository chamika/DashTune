package com.chamika.dashtune

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
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

    @Test
    fun `shouldScheduleReporter returns true only when both isPlaying and isAudiobook are true`() {
        assertTrue(DashTuneMusicService.shouldScheduleReporter(isPlaying = true, isPlayingAudiobook = true))
    }

    @Test
    fun `shouldScheduleReporter returns false when not playing`() {
        assertFalse(DashTuneMusicService.shouldScheduleReporter(isPlaying = false, isPlayingAudiobook = true))
    }

    @Test
    fun `shouldScheduleReporter returns false when not an audiobook`() {
        assertFalse(DashTuneMusicService.shouldScheduleReporter(isPlaying = true, isPlayingAudiobook = false))
    }

    @Test
    fun `shouldScheduleReporter returns false when neither playing nor audiobook`() {
        assertFalse(DashTuneMusicService.shouldScheduleReporter(isPlaying = false, isPlayingAudiobook = false))
    }

    // --- isTransientNetworkError tests ---

    @Test
    fun `isTransientNetworkError returns true for network connection failed`() {
        assertTrue(
            DashTuneMusicService.isTransientNetworkError(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            )
        )
    }

    @Test
    fun `isTransientNetworkError returns true for network connection timeout`() {
        assertTrue(
            DashTuneMusicService.isTransientNetworkError(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            )
        )
    }

    @Test
    fun `isTransientNetworkError returns false for bad HTTP status`() {
        // A reachable server returning an error status is not a connectivity problem;
        // skipping to the next item is the right recovery.
        assertFalse(
            DashTuneMusicService.isTransientNetworkError(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            )
        )
    }

    @Test
    fun `isTransientNetworkError returns false for decoding failure`() {
        assertFalse(
            DashTuneMusicService.isTransientNetworkError(
                PlaybackException.ERROR_CODE_DECODING_FAILED
            )
        )
    }

    @Test
    fun `isTransientNetworkError returns false for file not found`() {
        assertFalse(
            DashTuneMusicService.isTransientNetworkError(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            )
        )
    }

    @Test
    fun `network error retry window covers a cold boot`() {
        // Retries must span at least ~2 minutes so the head unit has time to bring up
        // its data connection after a cold start.
        val totalWindowMs =
            DashTuneMusicService.MAX_NETWORK_ERROR_RETRIES * DashTuneMusicService.NETWORK_ERROR_RETRY_DELAY_MS
        assertTrue(totalWindowMs >= 120_000L)
    }

    // --- isAudiobookTrack tests ---

    @Test
    fun `isAudiobookTrack returns true when IS_AUDIOBOOK_KEY is set`() {
        val extras = Bundle().apply { putBoolean(IS_AUDIOBOOK_KEY, true) }
        val item = MediaItem.Builder()
            .setMediaId("book-1")
            .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build())
            .build()
        assertTrue(DashTuneMusicService.isAudiobookTrack(item))
    }

    @Test
    fun `isAudiobookTrack returns false for regular music item`() {
        val item = MediaItem.Builder()
            .setMediaId("track-1")
            .setMediaMetadata(MediaMetadata.Builder().build())
            .build()
        assertFalse(DashTuneMusicService.isAudiobookTrack(item))
    }

    @Test
    fun `isAudiobookTrack returns false when IS_AUDIOBOOK_KEY is explicitly false`() {
        val extras = Bundle().apply { putBoolean(IS_AUDIOBOOK_KEY, false) }
        val item = MediaItem.Builder()
            .setMediaId("track-1")
            .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build())
            .build()
        assertFalse(DashTuneMusicService.isAudiobookTrack(item))
    }

    @Test
    fun `isAudiobookTrack returns false for null item`() {
        assertFalse(DashTuneMusicService.isAudiobookTrack(null))
    }
}
