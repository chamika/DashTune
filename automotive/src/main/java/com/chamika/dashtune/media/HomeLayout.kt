package com.chamika.dashtune.media

import android.content.Context

/**
 * How many tiles fit on one row of the Home tab.
 *
 * AAOS never tells an app how many columns its grid has, so the display width is the only
 * signal available. Each section is trimmed to this count so it occupies exactly one row and
 * nothing wraps into a ragged second line.
 */
object HomeLayout {

    /** Below this the head unit's grid fits three tiles per row, at or above it four. */
    const val LARGE_SCREEN_MIN_WIDTH_DP = 1200

    const val TILES_SMALL_SCREEN = 3
    const val TILES_LARGE_SCREEN = 4

    fun tilesPerRow(context: Context): Int =
        if (context.resources.configuration.screenWidthDp >= LARGE_SCREEN_MIN_WIDTH_DP) {
            TILES_LARGE_SCREEN
        } else {
            TILES_SMALL_SCREEN
        }
}
