package com.chamika.dashtune

import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AlbumArtContentProviderExtendedTest {

    @After
    fun tearDown() {
        AlbumArtContentProvider.clearCache(File(System.getProperty("java.io.tmpdir")))
    }

    // --- URI format edge cases ---

    @Test
    fun `mapUri handles URI with port number`() {
        val httpUri = Uri.parse("http://192.168.1.100:8096/Items/abc123/Images/Primary")

        val contentUri = AlbumArtContentProvider.mapUri(httpUri)

        assertEquals("content", contentUri.scheme)
        assertEquals("com.chamika.dashtune", contentUri.authority)
        assertNotNull(contentUri.path)
    }

    @Test
    fun `mapUri handles https URI`() {
        val httpsUri = Uri.parse("https://jellyfin.example.com/Items/abc123/Images/Primary")

        val contentUri = AlbumArtContentProvider.mapUri(httpsUri)

        assertEquals("content", contentUri.scheme)
        assertNotNull(contentUri.path)
    }

    @Test
    fun `mapUri handles URI with query parameters`() {
        val httpUri = Uri.parse("http://example.com/Items/abc123/Images/Primary?quality=90&maxWidth=256")

        val contentUri = AlbumArtContentProvider.mapUri(httpUri)

        assertEquals("content", contentUri.scheme)
        // Path should be derived from encoded path portion only
        assertNotNull(contentUri.path)
    }

    @Test
    fun `originalUri correctly maps back after mapUri with port number`() {
        val httpUri = Uri.parse("http://192.168.1.100:8096/Items/abc123/Images/Primary")
        val contentUri = AlbumArtContentProvider.mapUri(httpUri)

        val result = AlbumArtContentProvider.originalUri(contentUri)

        assertEquals(httpUri, result)
    }

    @Test
    fun `mapUri handles long nested paths`() {
        val httpUri = Uri.parse("http://example.com/Items/abc123/Images/Primary/Extra/Deep")

        val contentUri = AlbumArtContentProvider.mapUri(httpUri)

        assertEquals("content", contentUri.scheme)
        // Path should replace all / with :
        val path = contentUri.path
        assertNotNull(path)
        assertTrue(path!!.contains(":"))
    }

    // --- clearCache file deletion tests ---

    @Test
    fun `clearCache removes entries from uriMap`() {
        val httpUri1 = Uri.parse("http://example.com/Items/item1/Images/Primary")
        val httpUri2 = Uri.parse("http://example.com/Items/item2/Images/Primary")
        val contentUri1 = AlbumArtContentProvider.mapUri(httpUri1)
        val contentUri2 = AlbumArtContentProvider.mapUri(httpUri2)

        AlbumArtContentProvider.clearCache(File(System.getProperty("java.io.tmpdir")))

        assertNull(AlbumArtContentProvider.originalUri(contentUri1))
        assertNull(AlbumArtContentProvider.originalUri(contentUri2))
    }

    // --- Multiple mappings tests ---

    @Test
    fun `multiple different URIs can coexist in the map`() {
        val uris = (1..10).map { Uri.parse("http://example.com/Items/item$it/Images/Primary") }
        val contentUris = uris.map { AlbumArtContentProvider.mapUri(it) }

        // All mappings should resolve back to original
        uris.forEachIndexed { index, httpUri ->
            assertEquals(httpUri, AlbumArtContentProvider.originalUri(contentUris[index]))
        }
    }

    @Test
    fun `content URI paths are unique for different source URIs`() {
        val httpUri1 = Uri.parse("http://example.com/Items/abc/Images/Primary")
        val httpUri2 = Uri.parse("http://example.com/Items/def/Images/Primary")

        val contentUri1 = AlbumArtContentProvider.mapUri(httpUri1)
        val contentUri2 = AlbumArtContentProvider.mapUri(httpUri2)

        assertTrue(contentUri1.path != contentUri2.path)
    }

    // --- Empty path tests ---

    @Test
    fun `mapUri returns EMPTY for URI with empty encoded path`() {
        // A URI whose encodedPath is "/" produces an empty path after substring(1).
        // mapUri returns Uri.EMPTY in that case (the ?: branch).
        val rootPathUri = Uri.parse("http://example.com/")

        val contentUri = AlbumArtContentProvider.mapUri(rootPathUri)

        assertNotNull(contentUri)
        // path "/" → substring(1) → "" → the resulting content URI has an empty path
        assertEquals("", contentUri.path ?: "")
    }
}
