package com.chamika.dashtune

import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import org.junit.Assert.assertEquals
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
}
