package com.chamika.dashtune.media

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS
import io.mockk.mockk
import org.jellyfin.sdk.api.client.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JellyfinMediaTreeTest {

    private lateinit var context: Context
    private lateinit var tree: JellyfinMediaTree

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Ensure clean preference state before each test
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        tree = JellyfinMediaTree(
            context = context,
            api = mockk<ApiClient>(relaxed = true),
            itemFactory = mockk<MediaItemFactory>(relaxed = true)
        )
    }

    @Test
    fun `getActiveCategoryIds returns the four default categories when no preference is set`() {
        val ids = tree.getActiveCategoryIds()

        assertEquals(4, ids.size)
        assertTrue(ids.contains(LATEST_ALBUMS))
        assertTrue(ids.contains(FAVOURITES))
        assertTrue(ids.contains(BOOKS))
        assertTrue(ids.contains(PLAYLISTS))
    }

    @Test
    fun `getActiveCategoryIds preserves canonical order regardless of selection order`() {
        // Store "books" before "latest" to verify canonical ordering is applied
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putStringSet("browse_categories", setOf("books", "latest"))
            .commit()

        val ids = tree.getActiveCategoryIds()

        assertEquals(2, ids.size)
        assertEquals(LATEST_ALBUMS, ids[0])
        assertEquals(BOOKS, ids[1])
    }

    @Test
    fun `getActiveCategoryIds includes random albums when that category is selected`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putStringSet("browse_categories", setOf("latest", "favourites", "random"))
            .commit()

        val ids = tree.getActiveCategoryIds()

        assertEquals(3, ids.size)
        assertTrue(ids.contains(LATEST_ALBUMS))
        assertTrue(ids.contains(FAVOURITES))
        assertTrue(ids.contains(RANDOM_ALBUMS))
    }

    @Test
    fun `getActiveCategoryIds falls back to defaults when an empty set is stored`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putStringSet("browse_categories", emptySet())
            .commit()

        val ids = tree.getActiveCategoryIds()

        assertEquals(4, ids.size)
        assertTrue(ids.contains(LATEST_ALBUMS))
        assertTrue(ids.contains(FAVOURITES))
        assertTrue(ids.contains(BOOKS))
        assertTrue(ids.contains(PLAYLISTS))
    }

    @Test
    fun `getActiveCategoryIds filters out invalid category keys`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putStringSet("browse_categories", setOf("latest", "invalid_key", "bogus"))
            .commit()

        val ids = tree.getActiveCategoryIds()

        assertEquals(1, ids.size)
        assertEquals(LATEST_ALBUMS, ids[0])
    }

    @Test
    fun `getActiveCategoryIds falls back to defaults when only invalid keys are stored`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putStringSet("browse_categories", setOf("bad_key", "another_bad_key"))
            .commit()

        val ids = tree.getActiveCategoryIds()

        assertEquals(4, ids.size)
        assertTrue(ids.contains(LATEST_ALBUMS))
        assertTrue(ids.contains(FAVOURITES))
        assertTrue(ids.contains(BOOKS))
        assertTrue(ids.contains(PLAYLISTS))
    }

    @Test
    fun `getActiveCategoryIds returns all five categories in canonical order`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putStringSet(
                "browse_categories",
                setOf("random", "playlists", "books", "favourites", "latest")
            )
            .commit()

        val ids = tree.getActiveCategoryIds()

        assertEquals(5, ids.size)
        assertEquals(LATEST_ALBUMS, ids[0])
        assertEquals(FAVOURITES, ids[1])
        assertEquals(BOOKS, ids[2])
        assertEquals(PLAYLISTS, ids[3])
        assertEquals(RANDOM_ALBUMS, ids[4])
    }
}
