package com.limelight.hydra

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Minimal enrollment screen for Phase 1 development.
 *
 * The production flow scans the fleet QR code with the passthrough camera.
 * TODO(#544): add QR scanning. Do not add a QR dependency yet; evaluate
 * CameraX plus ML Kit against a plain zxing-core decode first, and check
 * what Horizon OS allows for camera access on managed devices.
 *
 * For now the server URL and enrollment token arrive either typed into the
 * text fields or via intent extras, which adb can set:
 *
 *   adb shell am start \
 *     -n com.experiencenet.hydraheadquest/com.limelight.hydra.HydraEnrollmentActivity \
 *     --es server_url https://hydracluster.example.com \
 *     --es enrollment_token TOKEN
 *
 * The UI is built in code so the scaffold adds no layout resources.
 */
class HydraEnrollmentActivity : Activity() {

    companion object {
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_ENROLLMENT_TOKEN = "enrollment_token"
    }

    private lateinit var store: HydraConfigStore
    private lateinit var statusView: TextView
    private lateinit var serverUrlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var enrollButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = HydraConfigStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Hydra Head Enrollment"
            textSize = 24f
        }
        root.addView(title)

        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }
        root.addView(statusView)

        serverUrlField = EditText(this).apply {
            hint = "Server URL (https://...)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        root.addView(serverUrlField, matchWidth())

        tokenField = EditText(this).apply {
            hint = "Enrollment token"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        root.addView(tokenField, matchWidth())

        enrollButton = Button(this).apply {
            text = "Enroll"
            setOnClickListener { startEnrollment() }
        }
        root.addView(enrollButton, matchWidth())

        val resetButton = Button(this).apply {
            text = "Reset enrollment"
            setOnClickListener {
                store.clear()
                refreshStatus()
            }
        }
        root.addView(resetButton, matchWidth())

        val scroll = ScrollView(this)
        scroll.addView(
            root,
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

    private fun matchWidth(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 }
    }

    private fun refreshStatus() {
        val config = store.load()
        if (config != null) {
            statusView.text = "Enrolled as head ${config.headId}\nServer: ${config.serverUrl}"
            enrollButton.isEnabled = false
        } else {
            statusView.text = "Not enrolled. Scan the fleet QR code (TODO #544) " +
                    "or enter the server URL and token."
            enrollButton.isEnabled = true
        }
    }

    private fun startEnrollment() {
        val serverUrl = serverUrlField.text.toString().trim()
        val token = tokenField.text.toString().trim()
        if (serverUrl.isEmpty() || token.isEmpty()) {
            statusView.text = "Server URL and enrollment token are both required."
            return
        }
        enrollButton.isEnabled = false
        statusView.text = "Enrolling..."
        val name = defaultHeadName()
        thread(name = "HydraEnroll") {
            try {
                val config = HydraClusterClient.enroll(serverUrl, token, name)
                store.save(config)
                runOnUiThread {
                    refreshStatus()
                    statusView.text = "Enrolled as head ${config.headId} (name: $name)"
                    // TODO(#544): hand off to the catalog grid activity and
                    // start HydraState (30 s tick + 3 s command poll).
                }
            } catch (e: IOException) {
                runOnUiThread {
                    statusView.text = "Enrollment failed: ${e.message}"
                    enrollButton.isEnabled = true
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
