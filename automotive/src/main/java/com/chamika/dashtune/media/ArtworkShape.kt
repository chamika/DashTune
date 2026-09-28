package com.chamika.dashtune.media

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Corner radius as a fraction of the artwork's shorter side. Relative rather than absolute
 * because the host scales the image to whatever tile size it lays out.
 */
const val ARTWORK_CORNER_FRACTION = 0.06f

/**
 * Rounds the corners of [src] into a new ARGB bitmap with transparent corners.
 *
 * The AAOS host decides tile shape and some hosts (the reference Media Center) draw square
 * tiles, so the rounding is baked into the artwork itself. On a host that already rounds its
 * tiles this is invisible, since its radius clips ours.
 */
internal fun roundCorners(src: Bitmap, fraction: Float = ARTWORK_CORNER_FRACTION): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val radius = minOf(src.width, src.height) * fraction
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    Canvas(out).drawRoundRect(
        RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
        radius,
        radius,
        paint
    )
    return out
}
