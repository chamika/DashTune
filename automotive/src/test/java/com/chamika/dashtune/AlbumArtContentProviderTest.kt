package com.chamika.dashtune

import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AlbumArtContentProviderTest {

    @After
    fun tearDown() {
        // Clear the static uriMap after each test to prevent state leakage
        AlbumArtContentProvider.clearCache(File(System.getProperty("java.io.tmpdir")))
    }

    @Test
    fun `mapUri converts http URI to content URI with correct scheme and authority`() {
        val httpUri = Uri.parse("http://example.com/Items/abc123/Images/Primary")

        val contentUri = AlbumArtContentProvider.mapUri(httpUri)

        assertEquals("content", contentUri.scheme)
        assertEquals("com.chamika.dashtune", contentUri.authority)
    }

    @Test
    fun `mapUri replaces forward slashes in path with colons`() {
        val httpUri = Uri.parse("http://example.com/Items/abc123/Images/Primary")

        val contentUri = AlbumArtContentProvider.mapUri(httpUri)

        assertEquals("/Items:abc123:Images:Primary", contentUri.path)
    }

    @Test
    fun `originalUri returns the original http URI for a mapped content URI`() {
        val httpUri = Uri.parse("http://example.com/Items/test456/Images/Primary")
        val contentUri = AlbumArtContentProvider.mapUri(httpUri)

        val result = AlbumArtContentProvider.originalUri(contentUri)

        assertEquals(httpUri, result)
    }

    @Test
    fun `originalUri returns null for an unmapped content URI`() {
        val unknownUri = Uri.parse("content://com.chamika.dashtune/Unknown:id:Images:Primary")

        val result = AlbumArtContentProvider.originalUri(unknownUri)

        assertNull(result)
    }

    @Test
    fun `mapUri called twice with the same URI returns the same content URI`() {
        val httpUri = Uri.parse("http://example.com/Items/duplicate/Images/Primary")

        val first = AlbumArtContentProvider.mapUri(httpUri)
        val second = AlbumArtContentProvider.mapUri(httpUri)

        assertEquals(first, second)
    }

    @Test
    fun `mapUri for two different http URIs produces two different content URIs`() {
        val uri1 = Uri.parse("http://example.com/Items/itemA/Images/Primary")
        val uri2 = Uri.parse("http://example.com/Items/itemB/Images/Primary")

        val contentUri1 = AlbumArtContentProvider.mapUri(uri1)
        val contentUri2 = AlbumArtContentProvider.mapUri(uri2)

        assertNotEquals(contentUri1, contentUri2)
    }

    @Test
    fun `clearCache removes all stored URI mappings`() {
        val httpUri = Uri.parse("http://example.com/Items/toClear/Images/Primary")
        val contentUri = AlbumArtContentProvider.mapUri(httpUri)

        AlbumArtContentProvider.clearCache(File(System.getProperty("java.io.tmpdir")))

        assertNull(AlbumArtContentProvider.originalUri(contentUri))
    }

    @Test
    fun `mapUri is idempotent — originalUri still resolves after being mapped twice`() {
        val httpUri = Uri.parse("http://example.com/Items/idempotent/Images/Primary")
        AlbumArtContentProvider.mapUri(httpUri)
        val contentUri = AlbumArtContentProvider.mapUri(httpUri)

        assertEquals(httpUri, AlbumArtContentProvider.originalUri(contentUri))
    }
}
