package com.limelight.hydra

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.limelight.hydra.model.FleetEnrollQR
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Enrollment screen: QR scan first, manual entry as fallback.
 *
 * The production flow scans the fleet QR code (JSON with server_url and
 * enrollment_token, see docs/hydra-api-contract.md section 2) through
 * [HydraQrScanner]. On Meta Quest 3 / 3S the passthrough camera needs
 * Horizon OS v74+ and BOTH android.permission.CAMERA and
 * horizonos.permission.HEADSET_CAMERA granted at runtime. Where the camera
 * is absent or refused (older Quests, denied permission), the screen falls
 * back to manual entry with a short note. It never crashes on camera absence.
 *
 * The manual path stays fully supported. The server URL and enrollment token
 * can be typed into the text fields or passed as intent extras, which adb
 * can set:
 *
 *   adb shell am start \
 *     -n com.experiencenet.hydraheadquest/com.limelight.hydra.HydraEnrollmentActivity \
 *     --es server_url https://hydracluster.example.com \
 *     --es enrollment_token TOKEN
 *
 * The UI is built in code so the scaffold adds no layout resources. Styling
 * comes from [HydraUi]: the panel renders at a distance in VR, so text and
 * touch targets are sized up and the QR preview is 320 dp tall.
 */
class HydraEnrollmentActivity : Activity(), HydraQrScanner.Listener {

    companion object {
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_ENROLLMENT_TOKEN = "enrollment_token"

        /** Quest passthrough camera permission, Horizon OS v74+. */
        private const val HEADSET_CAMERA_PERMISSION = "horizonos.permission.HEADSET_CAMERA"

        private const val REQUEST_CAMERA_PERMISSIONS = 1
    }

    private lateinit var store: HydraConfigStore
    private lateinit var statusView: TextView
    private lateinit var previewView: TextureView
    private lateinit var scanButton: Button
    private lateinit var serverUrlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var enrollButton: Button

    private var scanner: HydraQrScanner? = null

    /** True between "Scan fleet QR" and a decode, error, or stop. */
    private var scanRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = HydraConfigStore(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                dp(HydraUi.SPACE_L), dp(HydraUi.SPACE_L),
                dp(HydraUi.SPACE_L), dp(HydraUi.SPACE_L)
            )
        }

        column.addView(HydraUi.title(this, "Hydra Head Enrollment"))

        statusView = TextView(this).apply {
            setTextColor(HydraUi.COLOR_TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.TEXT_BUTTON)
            gravity = Gravity.CENTER
            setPadding(0, dp(HydraUi.SPACE_M), 0, dp(HydraUi.SPACE_M))
        }
        column.addView(statusView)

        // Live preview for the QR scan. Hidden until a scan starts.
        previewView = TextureView(this).apply {
            visibility = View.GONE
            surfaceTextureListener = previewListener
        }
        column.addView(
            previewView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320)
            ).apply { topMargin = dp(HydraUi.SPACE_S) }
        )

        scanButton = HydraUi.bigButton(this, "Scan fleet QR") { requestScan() }
        column.addView(scanButton, matchWidth())

        serverUrlField = EditText(this).apply {
            hint = "Server URL (https://...)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        HydraUi.styleField(this, serverUrlField)
        column.addView(serverUrlField, matchWidth())

        tokenField = EditText(this).apply {
            hint = "Enrollment token"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        HydraUi.styleField(this, tokenField)
        column.addView(tokenField, matchWidth())

        enrollButton = HydraUi.bigButton(this, "Enroll", primary = true) {
            startManualEnrollment()
        }
        column.addView(enrollButton, matchWidth())

        val resetButton = HydraUi.bigButton(this, "Reset enrollment") {
            store.clear()
            refreshStatus()
        }
        column.addView(resetButton, matchWidth())

        // Cap the column width and center it on the panel.
        val frame = FrameLayout(this)
        frame.addView(
            column,
            FrameLayout.LayoutParams(
                dp(640),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
        val scroll = ScrollView(this).apply {
            setBackgroundColor(HydraUi.COLOR_BACKGROUND)
            isFillViewport = true
        }
        scroll.addView(
            frame,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(scroll)

        // Prefill from intent extras for adb-driven Phase 1 testing.
        intent?.getStringExtra(EXTRA_SERVER_URL)?.let { serverUrlField.setText(it) }
        intent?.getStringExtra(EXTRA_ENROLLMENT_TOKEN)?.let { tokenField.setText(it) }

        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        // Release the camera whenever the screen leaves the foreground.
        stopScanning()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScanning()
    }

    private fun matchWidth(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(HydraUi.SPACE_S) }
    }

    private fun dp(value: Int): Int = HydraUi.dp(this, value)

    /** Enabled state plus a visible cue; custom backgrounds have none. */
    private fun Button.setEnabledVisual(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
    }

    private fun refreshStatus() {
        val config = store.load()
        if (config != null) {
            statusView.text = "Enrolled as head ${config.headId}\nServer: ${config.serverUrl}"
            enrollButton.setEnabledVisual(false)
            scanButton.setEnabledVisual(false)
        } else {
            statusView.text = "Not enrolled. Scan the fleet QR code " +
                    "or enter the server URL and token."
            enrollButton.setEnabledVisual(true)
            scanButton.setEnabledVisual(true)
        }
    }

    // ------------------------------------------------------------------
    // QR scanning
    // ------------------------------------------------------------------

    /** "Scan fleet QR" tapped: get the permissions, then start the camera. */
    private fun requestScan() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Pre-M grants install-time permissions; just scan.
            startScanning()
            return
        }
        val missing = ArrayList<String>(2)
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.CAMERA)
        }
        // Only request HEADSET_CAMERA where the platform defines it
        // (Horizon OS v74+). Other devices would report a stuck denial.
        if (isPermissionKnown(HEADSET_CAMERA_PERMISSION) &&
            checkSelfPermission(HEADSET_CAMERA_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(HEADSET_CAMERA_PERMISSION)
        }
        if (missing.isEmpty()) {
            startScanning()
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_CAMERA_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSIONS) return
        var cameraGranted = true
        for (i in permissions.indices) {
            if (permissions[i] == Manifest.permission.CAMERA &&
                grantResults[i] != PackageManager.PERMISSION_GRANTED
            ) {
                cameraGranted = false
            }
        }
        if (cameraGranted) {
            // A denied HEADSET_CAMERA leaves camera enumeration empty on
            // Quest; the scanner reports that and we fall back cleanly.
            startScanning()
        } else {
            fallBackToManual("Camera permission denied.")
        }
    }

    private fun isPermissionKnown(permission: String): Boolean {
        return try {
            packageManager.getPermissionInfo(permission, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun startScanning() {
        stopScanning()
        scanRequested = true
        previewView.visibility = View.VISIBLE
        statusView.text = "Scan the fleet QR."
        val texture = if (previewView.isAvailable) previewView.surfaceTexture else null
        if (texture != null) {
            launchScanner(texture)
        }
        // Otherwise previewListener.onSurfaceTextureAvailable launches it.
    }

    private fun launchScanner(texture: SurfaceTexture) {
        if (!scanRequested || scanner != null) return
        scanner = HydraQrScanner(this, texture, this).also { it.start() }
    }

    private fun stopScanning() {
        scanRequested = false
        scanner?.stop()
        scanner = null
        previewView.visibility = View.GONE
    }

    /** Camera absent, refused, or broken: manual entry with a short note. */
    private fun fallBackToManual(reason: String) {
        stopScanning()
        statusView.text = "Camera scan unavailable: $reason\n" +
                "Enter the server URL and enrollment token below."
    }

    private val previewListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            surface: SurfaceTexture, width: Int, height: Int
        ) {
            launchScanner(surface)
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture, width: Int, height: Int
        ) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            scanner?.stop()
            scanner = null
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        }
    }

    // HydraQrScanner.Listener, called on the scanner's background thread.

    override fun onQrDecoded(text: String): Boolean {
        val qr = FleetEnrollQR.fromJson(text)
        if (qr == null) {
            // Not the fleet QR. Keep the camera running.
            runOnUiThread {
                if (scanRequested) {
                    statusView.text = "That QR is not a fleet enrollment code. " +
                            "Aim at the fleet QR."
                }
            }
            return false
        }
        runOnUiThread {
            stopScanning()
            // Mirror the payload into the fields so the operator sees what
            // was scanned (the token field is plain text on purpose; the
            // enrollment token is short lived and fleet scoped).
            serverUrlField.setText(qr.serverUrl)
            tokenField.setText(qr.enrollmentToken)
            enroll(qr.serverUrl, qr.enrollmentToken)
        }
        return true
    }

    override fun onScannerError(message: String) {
        runOnUiThread { fallBackToManual(message) }
    }

    // ------------------------------------------------------------------
    // Enrollment (shared by the QR and manual paths)
    // ------------------------------------------------------------------

    private fun startManualEnrollment() {
        val serverUrl = serverUrlField.text.toString().trim()
        val token = tokenField.text.toString().trim()
        if (serverUrl.isEmpty() || token.isEmpty()) {
            statusView.text = "Server URL and enrollment token are both required."
            return
        }
        stopScanning()
        enroll(serverUrl, token)
    }

    /**
     * POST /api/v1/heads with the fleet token, persist the per-device
     * config, and show the enrolled state. Identical for QR and manual.
     */
    private fun enroll(serverUrl: String, token: String) {
        enrollButton.setEnabledVisual(false)
        scanButton.setEnabledVisual(false)
        statusView.text = "Enrolling..."
        val name = defaultHeadName()
        thread(name = "HydraEnroll") {
            try {
                val config = HydraClusterClient.enroll(serverUrl, token, name)
                store.save(config)
                runOnUiThread {
                    refreshStatus()
                    statusView.text = "Enrolled as head ${config.headId} (name: $name)"
                    // Adopt the fresh enrollment (starts the 30 s tick and
                    // 3 s command poll) and hand off to the kiosk routing.
                    HydraApp.state(this).ensureStarted()
                    startActivity(Intent(this, HydraLaunchActivity::class.java))
                    finish()
                }
            } catch (e: IOException) {
                runOnUiThread {
                    statusView.text = "Enrollment failed: ${e.message}"
                    enrollButton.setEnabledVisual(true)
                    scanButton.setEnabledVisual(true)
                }
            }
        }
    }

    /**
     * Default head name: "quest-head-" plus the first 6 characters of the
     * device id. The name must be unique per device. Never reuse a shared
     * name like "ipad-head" (HydraGuard collision, hydracluster #449).
     */
    @SuppressLint("HardwareIds")
    private fun defaultHeadName(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"
        val suffix = androidId.lowercase().take(6).ifEmpty { "000000" }
        return "quest-head-$suffix"
    }
}
