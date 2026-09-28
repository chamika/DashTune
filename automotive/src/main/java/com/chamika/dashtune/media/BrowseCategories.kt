package com.chamika.dashtune.media

import com.chamika.dashtune.media.MediaItemFactory.Companion.ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ARTISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.FOLDERS
import com.chamika.dashtune.media.MediaItemFactory.Companion.GENRES
import com.chamika.dashtune.media.MediaItemFactory.Companion.HOME
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS

/**
 * The single source of truth for the root browse tabs: which preference key maps to which
 * node, and in what order they appear. The media tree and the settings migration both read
 * it, so a new category only has to be declared once (plus the two string arrays that give
 * it a label in Settings).
 */
object BrowseCategories {

    const val PREF_KEY = "browse_categories"

    /** The head unit shows at most this many root tabs. */
    const val MAX_SELECTED = 4

    const val MIN_SELECTED = 2

    /**
     * Preference key to node id, in the order tabs are laid out. Categories added after the
     * first release are appended rather than slotted in alphabetically, so upgrading never
     * shuffles the tabs an existing user already knows — Home is the exception, because it
     * is meant to be the first thing seen.
     */
    val CANONICAL_ORDER: List<Pair<String, String>> = listOf(
        "home" to HOME,
        "latest" to LATEST_ALBUMS,
        "favourites" to FAVOURITES,
        "books" to BOOKS,
        "playlists" to PLAYLISTS,
        "random" to RANDOM_ALBUMS,
        "folders" to FOLDERS,
        "artists" to ARTISTS,
        "albums" to ALBUMS,
        "genres" to GENRES
    )

    val DEFAULTS: Set<String> = setOf("home", "favourites", "books", "playlists")

    private val VALID_KEYS: Set<String> = CANONICAL_ORDER.map { it.first }.toSet()

    /** Drops unknown keys and falls back to the defaults when nothing valid is left. */
    fun sanitize(selected: Set<String>): Set<String> =
        selected.intersect(VALID_KEYS).ifEmpty { DEFAULTS }

    /** Node ids for [selected], in canonical tab order. */
    fun nodeIds(selected: Set<String>): List<String> {
        val valid = sanitize(selected)
        return CANONICAL_ORDER.filter { it.first in valid }.map { it.second }
    }

    /** [selected] in canonical tab order, as preference keys. */
    fun ordered(selected: Set<String>): List<String> {
        val valid = sanitize(selected)
        return CANONICAL_ORDER.map { it.first }.filter { it in valid }
    }
}
