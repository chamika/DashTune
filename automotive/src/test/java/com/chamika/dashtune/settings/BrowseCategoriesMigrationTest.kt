package com.chamika.dashtune.settings

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.chamika.dashtune.media.BrowseCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The migration exists because a stored category set beats the default, so an upgrading
 * user would never see the Home tab without it. These cover that it lands, that it respects
 * the four-tab cap, and that it never runs twice (which would keep re-adding Home after the
 * user deliberately removed it).
 */
@RunWith(RobolectricTestRunner::class)
class BrowseCategoriesMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(context)

    private fun store(vararg keys: String) {
        prefs().edit().putStringSet(BrowseCategories.PREF_KEY, keys.toSet()).commit()
    }

    private fun stored(): Set<String>? = prefs().getStringSet(BrowseCategories.PREF_KEY, null)

    @Test
    fun `adds home to a stored selection that has room`() {
        store("latest", "favourites")

        BrowseCategoriesMigration.run(prefs())

        assertEquals(setOf("home", "latest", "favourites"), stored())
    }

    @Test
    fun `drops the last tab when the selection is already full`() {
        store("latest", "favourites", "books", "playlists")

        BrowseCategoriesMigration.run(prefs())

        // Playlists is last in canonical order, so it makes way for Home.
        assertEquals(setOf("home", "latest", "favourites", "books"), stored())
    }

    @Test
    fun `drops from the end of canonical order, not of the stored set`() {
        // Stored as a set, so insertion order must not decide what is dropped.
        store("genres", "latest", "albums", "favourites")

        BrowseCategoriesMigration.run(prefs())

        assertEquals(setOf("home", "latest", "favourites", "albums"), stored())
    }

    @Test
    fun `leaves an untouched preference alone so the default applies`() {
        BrowseCategoriesMigration.run(prefs())

        assertNull(stored())
    }

    @Test
    fun `does nothing when home is already selected`() {
        store("home", "latest")

        BrowseCategoriesMigration.run(prefs())

        assertEquals(setOf("home", "latest"), stored())
    }

    @Test
    fun `runs only once so a removed home tab stays removed`() {
        store("latest", "favourites")
        BrowseCategoriesMigration.run(prefs())

        // The user then removes Home again.
        store("latest", "favourites")
        BrowseCategoriesMigration.run(prefs())

        assertEquals(setOf("latest", "favourites"), stored())
    }

    @Test
    fun `result never exceeds the tab cap`() {
        store("latest", "favourites", "books", "playlists", "random", "folders")

        BrowseCategoriesMigration.run(prefs())

        val result = stored()!!
        assertTrue(result.size <= BrowseCategories.MAX_SELECTED)
        assertTrue(result.contains("home"))
    }
}
