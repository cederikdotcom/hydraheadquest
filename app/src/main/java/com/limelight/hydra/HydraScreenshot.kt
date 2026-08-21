package com.limelight.hydra

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JPEG capture of the current activity's window for the remote screenshot
 * command (contract section 9). Uses PixelCopy, which reads the composited
 * window including SurfaceView content on API 26+.
 *
 * Known caveat, same as the Metal layer on iPad: the streaming video
 * surface may still capture black on some devices. TODO(#544):
 * MediaProjection fallback if Quest captures black during streams. The
 * wire contract (raw JPEG POST) stays the same either way.
 */
object HydraScreenshot {

    private const val TAG = "HydraScreenshot"

    /** JPEG quality, matching the iPad's 0.7. */
    private const val JPEG_QUALITY = 70

    /** Cap on waiting for the PixelCopy callback. */
    private const val COPY_TIMEOUT_SECONDS = 4L

    /**
     * Capture the activity's window as JPEG bytes. Returns null when the
     * platform is too old, the window has no size yet, or the copy fails.
     * Blocking; call from a background thread (the command poll does).
     */
    fun capture(activity: Activity): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "PixelCopy needs API 26+")
            return null
        }
        val decor = activity.window?.decorView ?: return null
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) {
            return null
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        val copyResult = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
        val thread = HandlerThread("HydraScreenshot")
        thread.start()
        try {
            PixelCopy.request(
                activity.window,
                bitmap,
                { result ->
                    copyResult.set(result)
                    latch.countDown()
                },
                Handler(thread.looper)
            )
            if (!latch.await(COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "PixelCopy timed out")
                return null
            }
            if (copyResult.get() != PixelCopy.SUCCESS) {
                Log.w(TAG, "PixelCopy failed: ${copyResult.get()}")
                return null
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            return out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "screenshot capture failed: ${e.message}")
            return null
        } finally {
            thread.quitSafely()
            bitmap.recycle()
        }
    }
}
