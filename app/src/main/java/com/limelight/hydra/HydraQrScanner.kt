package com.limelight.hydra

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous QR scan on Camera2 plus zxing-core (issue #544).
 *
 * The scanner opens the first back or external camera, streams low resolution
 * YUV_420_888 frames through an [ImageReader], and feeds the Y plane to zxing
 * (PlanarYUVLuminanceSource + HybridBinarizer + MultiFormatReader restricted
 * to QR). All camera callbacks and decoding run on one background
 * [HandlerThread]; nothing here touches the main thread.
 *
 * On Meta Quest 3 / 3S the passthrough camera shows up through Camera2 only
 * on Horizon OS v74 or newer, and only when BOTH android.permission.CAMERA
 * and horizonos.permission.HEADSET_CAMERA are granted at runtime. On older
 * Quests, or when a permission is refused, camera enumeration returns an
 * empty list. That case surfaces as [Listener.onScannerError], never as a
 * crash, so the caller can fall back to manual entry.
 *
 * Lifecycle: construct, [start] once, and the scanner runs until the listener
 * accepts a decode, an error is delivered, or [stop] is called. Every path
 * releases the camera, the ImageReader, and the thread. A stopped scanner
 * cannot be restarted; create a new one.
 */
class HydraQrScanner(
    context: Context,
    /**
     * Optional live preview target. When set, the scanner picks the capture
     * size, calls [SurfaceTexture.setDefaultBufferSize] on it, and adds it to
     * the capture session. Null scans headless.
     */
    private val previewTexture: SurfaceTexture?,
    private val listener: Listener
) {

    /**
     * Scan callbacks. Both methods are called on the scanner's background
     * thread; hop to the main thread before touching views.
     */
    interface Listener {
        /**
         * A QR code was decoded. Return true to accept it: the scanner stops
         * and never calls back again. Return false to keep scanning (for
         * example when the payload is not fleet enrollment JSON). A rejected
         * payload is not re-delivered for [REJECT_RETRY_MS] unless a
         * different payload decodes in between.
         */
        fun onQrDecoded(text: String): Boolean

        /**
         * Scanning cannot run or cannot continue: no camera present,
         * permission denied, camera in use, or a session failure. Delivered
         * at most once; the scanner has already released its resources.
         */
        fun onScannerError(message: String)
    }

    companion object {
        private const val TAG = "HydraQrScanner"

        /** Do not re-deliver an identical rejected payload within this window. */
        const val REJECT_RETRY_MS = 2000L

        /** Smallest capture size the picker aims for. QR needs no more. */
        private const val MIN_WIDTH = 640
        private const val MIN_HEIGHT = 480
    }

    private val appContext: Context = context.applicationContext

    private val handlerThread = HandlerThread("HydraQrScan")
    private lateinit var handler: Handler

    /** Set once by start(), stop(), or fail(); all callbacks check it. */
    private val stopped = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var lastRejectedText: String? = null
    private var lastRejectedAt = 0L

    private val qrReader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    /** Begin scanning. Safe to call once; later calls are ignored. */
    fun start() {
        if (stopped.get()) return
        if (!started.compareAndSet(false, true)) return
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        handler.post { openInternal() }
    }

    /**
     * Stop scanning and release everything. Idempotent, callable from any
     * thread. No callbacks are delivered after stop.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        if (!started.get()) return
        handler.post { releaseInternal() }
        // quitSafely still runs the release runnable posted above.
        handlerThread.quitSafely()
    }

    /** Deliver the error once, then release. Runs on the handler thread. */
    private fun fail(message: String) {
        if (!stopped.compareAndSet(false, true)) return
        Log.w(TAG, "QR scan unavailable: $message")
        releaseInternal()
        handlerThread.quitSafely()
        listener.onScannerError(message)
    }

    // ------------------------------------------------------------------
    // Camera bring-up (handler thread)
    // ------------------------------------------------------------------

    private fun openInternal() {
        if (stopped.get()) return
        try {
            val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return fail("Camera service unavailable")
            val cameraId = pickCameraId(manager)
                ?: return fail("No camera on this device")
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return fail("Camera reports no stream configuration")
            val size = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888))
                ?: return fail("Camera offers no YUV output")
            val reader = ImageReader.newInstance(
                size.width, size.height, ImageFormat.YUV_420_888, 2
            )
            reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)
            imageReader = reader
            previewTexture?.setDefaultBufferSize(size.width, size.height)
            manager.openCamera(cameraId, deviceCallback, handler)
        } catch (e: SecurityException) {
            fail("Camera permission denied")
        } catch (e: CameraAccessException) {
            fail("Camera access failed: ${e.message}")
        } catch (e: Exception) {
            fail("Camera init failed: ${e.message}")
        }
    }

    /**
     * First back-facing camera wins (the Quest passthrough cameras enumerate
     * as back-facing), then external, then anything else. Null when the
     * device has no camera or enumeration is empty (older Quests, or the
     * HEADSET_CAMERA permission was refused).
     */
    private fun pickCameraId(manager: CameraManager): String? {
        val ids = try {
            manager.cameraIdList
        } catch (e: Exception) {
            return null
        }
        if (ids.isEmpty()) return null
        var fallback: String? = null
        for (id in ids) {
            val facing = try {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            } catch (e: Exception) {
                null
            }
            when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> return id
                CameraCharacteristics.LENS_FACING_EXTERNAL ->
                    if (fallback == null) fallback = id
                else -> if (fallback == null) fallback = id
            }
        }
        return fallback
    }

    /**
     * Smallest size at or above 640x480: enough pixels for a QR, cheap to
     * decode. When the camera only offers smaller sizes, take the largest.
     */
    private fun pickSize(sizes: Array<Size>?): Size? {
        if (sizes == null || sizes.isEmpty()) return null
        var best: Size? = null
        for (s in sizes) {
            val longSide = maxOf(s.width, s.height)
            val shortSide = minOf(s.width, s.height)
            if (longSide < MIN_WIDTH || shortSide < MIN_HEIGHT) continue
            val current = best
            if (current == null ||
                s.width.toLong() * s.height < current.width.toLong() * current.height
            ) {
                best = s
            }
        }
        if (best != null) return best
        return sizes.maxByOrNull { it.width.toLong() * it.height }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            if (stopped.get()) {
                device.close()
                return
            }
            cameraDevice = device
            createSession(device)
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            if (cameraDevice === device) cameraDevice = null
            fail("Camera disconnected")
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            if (cameraDevice === device) cameraDevice = null
            val reason = when (error) {
                ERROR_CAMERA_IN_USE -> "Camera in use by another app"
                ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras in use"
                ERROR_CAMERA_DISABLED -> "Camera disabled by policy"
                ERROR_CAMERA_DEVICE -> "Camera device failure"
                else -> "Camera error $error"
            }
            fail(reason)
        }
    }

    private fun createSession(device: CameraDevice) {
        val reader = imageReader ?: return fail("ImageReader missing")
        val targets = ArrayList<Surface>(2)
        targets.add(reader.surface)
        val texture = previewTexture
        if (texture != null) {
            val surface = Surface(texture)
            previewSurface = surface
            targets.add(surface)
        }
        try {
            // The List/Handler overload is deprecated but is the only one
            // available at minSdk 21. SessionConfiguration needs API 28.
            @Suppress("DEPRECATION")
            device.createCaptureSession(targets, sessionCallback, handler)
        } catch (e: Exception) {
            fail("Camera session failed: ${e.message}")
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            if (stopped.get()) {
                session.close()
                return
            }
            captureSession = session
            val device = cameraDevice ?: return
            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                imageReader?.surface?.let { builder.addTarget(it) }
                previewSurface?.let { builder.addTarget(it) }
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                session.setRepeatingRequest(builder.build(), null, handler)
            } catch (e: Exception) {
                fail("Camera start failed: ${e.message}")
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            fail("Camera session configuration failed")
        }
    }

    // ------------------------------------------------------------------
    // Frame decode (handler thread)
    // ------------------------------------------------------------------

    private fun onFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            if (stopped.get()) return
            val text = decode(image) ?: return
            val now = SystemClock.elapsedRealtime()
            if (text == lastRejectedText && now - lastRejectedAt < REJECT_RETRY_MS) return
            // Never log the payload; the fleet QR carries the enrollment token.
            if (listener.onQrDecoded(text)) {
                stop()
            } else {
                lastRejectedText = text
                lastRejectedAt = now
            }
        } finally {
            image.close()
        }
    }

    /** Feed the Y plane to zxing. Null when no QR is in this frame. */
    private fun decode(image: Image): String? {
        val luminance = extractLuminance(image) ?: return null
        return try {
            val source = PlanarYUVLuminanceSource(
                luminance, image.width, image.height,
                0, 0, image.width, image.height, false
            )
            qrReader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: ReaderException) {
            null
        } catch (e: Exception) {
            // zxing can throw on degenerate frames; skip and keep scanning.
            null
        } finally {
            qrReader.reset()
        }
    }

    /**
     * Copy the Y plane into a compact width*height array, honoring the row
     * stride (and the pixel stride, which is 1 for the Y plane on every
     * conforming device).
     */
    private fun extractLuminance(image: Image): ByteArray? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val out = ByteArray(width * height)
            if (pixelStride == 1 && rowStride == width) {
                buffer.get(out)
            } else {
                val row = ByteArray(rowStride)
                var offset = 0
                for (y in 0 until height) {
                    buffer.position(y * rowStride)
                    val len = minOf(rowStride, buffer.remaining())
                    buffer.get(row, 0, len)
                    if (pixelStride == 1) {
                        System.arraycopy(row, 0, out, offset, width)
                    } else {
                        var x = 0
                        var i = 0
                        while (x < width && i < len) {
                            out[offset + x] = row[i]
                            x++
                            i += pixelStride
                        }
                    }
                    offset += width
                }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Teardown (handler thread)
    // ------------------------------------------------------------------

    private fun releaseInternal() {
        try {
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "session close failed: ${e.message}")
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "camera close failed: ${e.message}")
        }
        cameraDevice = null
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "reader close failed: ${e.message}")
        }
        imageReader = null
        try {
            previewSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "preview surface release failed: ${e.message}")
        }
        previewSurface = null
    }
}
