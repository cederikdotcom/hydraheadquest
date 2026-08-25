package com.limelight.hydra

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * Generates the "Ambient Dusk" environment: a small equirectangular
 * gradient rendered at runtime instead of a stored 4096x2048 photo.
 *
 * The look is a dark planetarium: deep blue-grey overhead, a soft
 * horizon glow slightly below eye level, near black at both poles.
 * At 1024x512 the texture is about 1/32 of the photo environments,
 * which were too heavy for the Quest 2 GPU next to a decoded stream.
 *
 * XrRenderer registers [ENTRY] as a synthetic first entry in the
 * environment picker and calls [render] instead of decoding an asset.
 * The equirect2 compositor layer behind it takes any 2:1 image size.
 */
object HydraAmbient {

    /**
     * Synthetic picker entry name. There is no file behind it. The
     * name has no extension on purpose: labelFor() turns it into the
     * picker label "Ambient Dusk" unchanged.
     */
    const val ENTRY = "ambient_dusk"

    @JvmStatic
    fun render(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()

        // Rows run zenith (v = 0) to nadir (v = 1), eye level is 0.5.
        // The glow band peaks at 0.56, just under eye level, so the
        // horizon reads as lit without putting light in the eyes.
        val sky = Paint()
        sky.isDither = true
        sky.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                0xFF06080D.toInt(), // zenith, near black blue
                0xFF141A26.toInt(), // upper sky, blue grey
                0xFF303C54.toInt(), // horizon glow
                0xFF10141D.toInt(), // floor
                0xFF05070B.toInt(), // nadir
            ),
            floatArrayOf(0.0f, 0.40f, 0.56f, 0.72f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, sky)

        // A wide soft brightening ahead of the viewer, so the room reads
        // as lit from one direction rather than as a uniform band. It
        // fades out before the side edges, so the horizontal wrap of the
        // equirect image has no visible seam.
        val glow = Paint()
        glow.isDither = true
        glow.shader = RadialGradient(
            w * 0.5f, h * 0.56f, w * 0.45f,
            intArrayOf(0x2E56688A, 0x0056688A),
            floatArrayOf(0.0f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, glow)

        return bitmap
    }
}
