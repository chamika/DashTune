package com.chamika.dashtune.media

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkShapeTest {

    private fun solid(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    @Test
    fun cornersBecomeTransparent() {
        val out = roundCorners(solid(100, 100))

        assertEquals(0, Color.alpha(out.getPixel(0, 0)))
        assertEquals(0, Color.alpha(out.getPixel(99, 0)))
        assertEquals(0, Color.alpha(out.getPixel(0, 99)))
        assertEquals(0, Color.alpha(out.getPixel(99, 99)))
    }

    @Test
    fun centreAndEdgeMidpointsStayOpaque() {
        val out = roundCorners(solid(100, 100))

        assertEquals(Color.RED, out.getPixel(50, 50))
        assertEquals(Color.RED, out.getPixel(50, 0))
        assertEquals(Color.RED, out.getPixel(0, 50))
    }

    @Test
    fun keepsSourceDimensions() {
        val out = roundCorners(solid(120, 80))

        assertEquals(120, out.width)
        assertEquals(80, out.height)
    }
}
