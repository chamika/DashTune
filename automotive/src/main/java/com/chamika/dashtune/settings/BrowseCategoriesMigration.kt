package com.chamika.dashtune.settings

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.media.BrowseCategories

/**
 * Puts the Home tab in front of the tabs an existing user already chose.
 *
 * Home is a new category, so upgrading users would never see it: their stored selection was
 * written before it existed and the stored value wins over the default. This runs once, adds
 * Home at the front, and drops tabs from the end until the selection fits the four-tab cap.
 */
object BrowseCategoriesMigration {

    private const val MIGRATED_KEY = "home_category_migrated"
    private const val HOME_KEY = "home"

    fun run(prefs: SharedPreferences) {
        if (prefs.getBoolean(MIGRATED_KEY, false)) return

        val stored = prefs.getStringSet(BrowseCategories.PREF_KEY, null)
        if (stored == null) {
            // Never customised, so the default set (which already leads with Home) applies.
            prefs.edit { putBoolean(MIGRATED_KEY, true) }
            return
        }

        if (stored.contains(HOME_KEY)) {
            prefs.edit { putBoolean(MIGRATED_KEY, true) }
            return
        }

        val ordered = BrowseCategories.ordered(stored.toSet()).toMutableList()
        ordered.add(0, HOME_KEY)
        while (ordered.size > BrowseCategories.MAX_SELECTED) {
            ordered.removeAt(ordered.lastIndex)
        }

        Log.i(LOG_TAG, "Migrating browse categories $stored -> $ordered")
        prefs.edit {
            putStringSet(BrowseCategories.PREF_KEY, ordered.toSet())
            putBoolean(MIGRATED_KEY, true)
        }
    }
}
