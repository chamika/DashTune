package com.chamika.dashtune.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AAOS never reports its grid column count, so Home derives the row size from the display
 * width. These pin the two head-unit classes the app actually ships against.
 */
@RunWith(RobolectricTestRunner::class)
class HomeLayoutTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "w1024dp-h768dp")
    fun `portrait-ish 1024dp head unit fits three tiles`() {
        assertEquals(HomeLayout.TILES_SMALL_SCREEN, HomeLayout.tilesPerRow(context))
    }

    @Test
    @Config(qualifiers = "w1440dp-h720dp")
    fun `wide 1440dp head unit fits four tiles`() {
        assertEquals(HomeLayout.TILES_LARGE_SCREEN, HomeLayout.tilesPerRow(context))
    }

    @Test
    @Config(qualifiers = "w1200dp-h800dp")
    fun `the threshold width itself counts as large`() {
        assertEquals(HomeLayout.TILES_LARGE_SCREEN, HomeLayout.tilesPerRow(context))
    }

    @Test
    @Config(qualifiers = "w1199dp-h800dp")
    fun `one dp below the threshold counts as small`() {
        assertEquals(HomeLayout.TILES_SMALL_SCREEN, HomeLayout.tilesPerRow(context))
    }
}
