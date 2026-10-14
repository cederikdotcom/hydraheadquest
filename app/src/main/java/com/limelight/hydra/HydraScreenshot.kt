package com.limelight.hydra

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * A flat stream captures fine, SurfaceView content included. An XR stream
 * cannot be captured here at all: it renders in a separate client app's XR
 * swapchain (issue #790). HydraState handles that case before calling this,
 * and uses [placeholder] to send the operator an honest labelled card. The
 * wire contract (raw JPEG POST) is the same for both.
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

    /**
     * Render a labelled card as JPEG bytes, for the cases where a real
     * capture is impossible but the operator still deserves an honest
     * frame instead of nothing or a black rectangle (issue #790). The
     * clearest example is an XR stream: the headset view renders in the
     * separate ALVR or WiVRn client's XR swapchain, which is another
     * process this app cannot read, so there is genuinely nothing in our
     * window to capture. This needs no Activity, so the command poll can
     * build it directly. Returns null only if drawing itself throws.
     */
    fun placeholder(title: String, detail: String): ByteArray? {
        return try {
            val width = 1024
            val height = 640
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(20, 22, 28))
                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 48f
                    textAlign = Paint.Align.CENTER
                }
                val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(170, 176, 188)
                    textSize = 30f
                    textAlign = Paint.Align.CENTER
                }
                val cx = width / 2f
                canvas.drawText(title, cx, height / 2f - 24f, titlePaint)
                drawWrapped(canvas, detail, cx, height / 2f + 32f, detailPaint, width - 120)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "placeholder render failed: ${e.message}")
            null
        }
    }

    /** Word wrap [text] to [maxWidth] px and draw it centred at [cx]. */
    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        cx: Float,
        startY: Float,
        paint: Paint,
        maxWidth: Int
    ) {
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (word in text.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                lines.add(line.toString())
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) {
            lines.add(line.toString())
        }
        var y = startY
        val lineHeight = paint.textSize * 1.3f
        for (l in lines) {
            canvas.drawText(l, cx, y, paint)
            y += lineHeight
        }
    }
}
