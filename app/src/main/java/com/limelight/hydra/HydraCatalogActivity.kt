package com.limelight.hydra

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.limelight.PcView
import com.limelight.hydra.model.Experience
import kotlin.concurrent.thread

/**
 * The kiosk self-service catalog, parity with the iPad ExperienceGridView.
 *
 * A dark full-screen grid of experience labels with big touch targets for
 * the controller pointer. Tapping a tile starts that experience as
 * self-service through HydraState (discovery, pairing, stream launch all
 * happen in the state machine and HydraStreamHooks). The activity renders
 * whatever state the machine is in: grid, starting overlay, streaming
 * overlay with a stop button, or an error with a dismiss button. When the
 * stream ends the machine returns to selfService and the grid reappears.
 *
 * Operator access (top right) is gated by the shared operator PIN, a
 * deterrent only, same as the iPad. Behind it: a diagnostics view showing
 * the same fields the heartbeat sends, issue reporting to the Hydra
 * tracker, the stock Moonlight UI for debugging, and enrollment reset.
 *
 * All UI is built in code; the Hydra layer adds no layout resources.
 */
class HydraCatalogActivity : Activity(), HydraState.Listener {

    companion object {
        /** Shared operator PIN. A deterrent, not security (iPad parity). */
        private const val OPERATOR_PIN = "1337"

        private const val GRID_COLUMNS = 3

        private const val COLOR_BACKGROUND = 0xFF0E0E12.toInt()
        private const val COLOR_TILE = 0xFF26262E.toInt()
        private const val COLOR_TEXT_DIM = 0xFF9A9AA5.toInt()
        private const val COLOR_TEXT_FAINT = 0xFF5C5C66.toInt()
    }

    private lateinit var hydraState: HydraState

    private lateinit var root: FrameLayout
    private lateinit var gridScroll: ScrollView
    private lateinit var grid: GridLayout
    private lateinit var emptyView: TextView
    private lateinit var statusPanel: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusMessage: TextView
    private lateinit var statusButton: Button
    private lateinit var identityView: TextView

    private var experiences: List<Experience> = emptyList()
    private var catalogLoaded = false
    private var routedToEnrollment = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hydraState = HydraApp.state(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        routedToEnrollment = false
        hydraState.ensureStarted()
        hydraState.listener = this
        experiences = hydraState.currentCatalog()
        catalogLoaded = catalogLoaded || experiences.isNotEmpty()
        render(hydraState.state)
        refreshIdentity()
    }

    override fun onPause() {
        if (hydraState.listener === this) {
            hydraState.listener = null
        }
        super.onPause()
    }

    // ------------------------------------------------------------------
    // HydraState.Listener (called on the state machine thread)
    // ------------------------------------------------------------------

    override fun onStateChanged(state: HydraState.State) {
        runOnUiThread {
            render(state)
            refreshIdentity()
        }
    }

    override fun onCatalogUpdated(experiences: List<Experience>) {
        runOnUiThread {
            catalogLoaded = true
            this.experiences = experiences
            if (gridScroll.visibility == View.VISIBLE) {
                populateGrid()
            }
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun render(state: HydraState.State) {
        when (state) {
            is HydraState.State.Unconfigured -> {
                if (!routedToEnrollment) {
                    routedToEnrollment = true
                    startActivity(Intent(this, HydraEnrollmentActivity::class.java))
                    finish()
                }
            }
            is HydraState.State.Idle -> showGrid()
            is HydraState.State.SelfService -> {
                catalogLoaded = catalogLoaded || state.experiences.isNotEmpty()
                if (state.experiences.isNotEmpty()) {
                    experiences = state.experiences
                }
                showGrid()
            }
            is HydraState.State.Discovering -> showStatus(
                "Starting ${state.experience.label}",
                "Finding a body for this experience...",
                null, null
            )
            is HydraState.State.Pairing -> showStatus(
                "Starting ${state.experience.label}",
                "Pairing with ${state.bodyName}...",
                null, null
            )
            is HydraState.State.Streaming -> showStatus(
                "Streaming ${state.experience.label}",
                "The experience is running.",
                "Stop"
            ) { hydraState.onUserStop() }
            is HydraState.State.Error -> showStatus(
                "Something went wrong",
                state.message,
                "Back to experiences"
            ) { hydraState.onErrorDismissed() }
        }
    }

    private fun showGrid() {
        statusPanel.visibility = View.GONE
        gridScroll.visibility = View.VISIBLE
        populateGrid()
    }

    private fun showStatus(
        title: String,
        message: String,
        buttonLabel: String?,
        buttonAction: (() -> Unit)? = null
    ) {
        gridScroll.visibility = View.GONE
        statusPanel.visibility = View.VISIBLE
        statusTitle.text = title
        statusMessage.text = message
        if (buttonLabel != null) {
            statusButton.visibility = View.VISIBLE
            statusButton.text = buttonLabel
            statusButton.setOnClickListener { buttonAction?.invoke() }
        } else {
            statusButton.visibility = View.GONE
        }
    }

    private fun populateGrid() {
        grid.removeAllViews()
        if (experiences.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            emptyView.text = if (catalogLoaded) {
                "No experiences available.\nCheck back later or contact your administrator."
            } else {
                "Loading experiences..."
            }
            return
        }
        emptyView.visibility = View.GONE
        for (experience in experiences) {
            grid.addView(makeTile(experience))
        }
    }

    private fun makeTile(experience: Experience): View {
        val tile = Button(this).apply {
            text = experience.label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setBackgroundColor(COLOR_TILE)
            minHeight = dp(140)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { hydraState.onExperienceTapped(experience) }
        }
        val params = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(dp(10), dp(10), dp(10), dp(10))
        }
        tile.layoutParams = params
        return tile
    }

    private fun refreshIdentity() {
        val config = hydraState.currentHeadConfig()
        val headId = hydraState.currentHeadId()
        val name = config?.name ?: headId ?: ""
        val location = listOfNotNull(config?.district, config?.venue)
            .filter { it.isNotEmpty() }
            .joinToString(" / ")
        identityView.text = listOf(name, location)
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    // ------------------------------------------------------------------
    // Operator access: PIN gate, diagnostics, issue report, reset
    // ------------------------------------------------------------------

    private fun promptOperatorPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Operator PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Operator access")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                if (input.text.toString() == OPERATOR_PIN) {
                    showDiagnostics()
                } else {
                    Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDiagnostics() {
        val diagText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "Collecting diagnostics..."
        }
        val scroll = ScrollView(this).apply {
            setPadding(dp(24), dp(16), dp(24), dp(16))
            addView(diagText)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Diagnostics")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Report issue", null)
            .setNegativeButton("More...", null)
            .create()
        dialog.show()

        // Collect off the main thread: the latency probe blocks up to 5 s.
        var lastReport = "Collecting diagnostics..."
        thread(name = "HydraDiag") {
            val report = buildDiagnosticsReport()
            lastReport = report
            runOnUiThread {
                if (dialog.isShowing) {
                    diagText.text = report
                }
            }
        }

        // Wire the buttons after show() so Report does not dismiss.
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
            fileIssue(lastReport)
        }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener {
            dialog.dismiss()
            showOperatorActions()
        }
    }

    /** The same fields the heartbeat sends, plus identity, for operators. */
    private fun buildDiagnosticsReport(): String {
        val d = hydraState.collectDiagnostics()
        val config = hydraState.currentHeadConfig()
        val lines = mutableListOf<String>()
        lines.add("head_id: ${hydraState.currentHeadId() ?: "-"}")
        lines.add("name: ${config?.name ?: "-"}")
        lines.add("district: ${config?.district ?: "-"}")
        lines.add("venue: ${config?.venue ?: "-"}")
        lines.add("state: ${hydraState.state.javaClass.simpleName}")
        lines.add("version: ${d.version}")
        lines.add("wireguard: ${d.wireguard}")
        lines.add("app: kiosk")
        lines.add("routing: ${d.routing}")
        lines.add("latency_ms: ${d.latencyMs}")
        lines.add("wifi_ssid: ${d.wifiSsid}")
        lines.add("local_ip: ${d.localIp}")
        lines.add("moonlight_client_id: ${d.moonlightClientId ?: "-"}")
        lines.add("stream_host: ${config?.streamHost ?: "-"}")
        lines.add("stream_app_id: ${config?.stream?.streamAppId ?: "-"}")
        return lines.joinToString("\n")
    }

    private fun fileIssue(report: String) {
        val config = hydraState.currentHeadConfig()
        val headId = hydraState.currentHeadId()
        val location = listOfNotNull(config?.district, config?.venue)
            .filter { it.isNotEmpty() }
            .joinToString("/")
        val title = if (location.isEmpty()) {
            "Quest diagnostics"
        } else {
            "Quest diagnostics - $location"
        }
        val description = "Diagnostic report from Quest head ${headId ?: "unknown"}\n\n$report"
        Toast.makeText(this, "Filing issue...", Toast.LENGTH_SHORT).show()
        thread(name = "HydraIssue") {
            try {
                HydraIssueReporter.report(
                    title, description, headId, config?.district, config?.venue
                )
                runOnUiThread {
                    Toast.makeText(this, "Issue filed", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Issue report failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Destructive and debug actions, already behind the PIN gate. */
    private fun showOperatorActions() {
        val actions = arrayOf(
            "Open Moonlight UI",
            "Open enrollment screen",
            "Reset enrollment"
        )
        AlertDialog.Builder(this)
            .setTitle("Operator actions")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, PcView::class.java))
                    1 -> startActivity(Intent(this, HydraEnrollmentActivity::class.java))
                    2 -> confirmReset()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset enrollment")
            .setMessage(
                "This forgets the head identity and stops the kiosk until " +
                    "the head is enrolled again. Continue?"
            )
            .setPositiveButton("Reset") { _, _ ->
                // reset() moves the machine to unconfigured; render() then
                // routes to the enrollment screen.
                hydraState.reset()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------
    // View construction
    // ------------------------------------------------------------------

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
        }

        // Experience grid.
        grid = GridLayout(this).apply {
            columnCount = GRID_COLUMNS
            useDefaultMargins = false
        }
        emptyView = TextView(this).apply {
            setTextColor(COLOR_TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(120), dp(24), dp(24))
        }
        val gridColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(72), dp(24), dp(24))
            addView(
                grid,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                emptyView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        gridScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                gridColumn,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            gridScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Status panel for discovering / pairing / streaming / error.
        statusTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
        }
        statusMessage = TextView(this).apply {
            setTextColor(COLOR_TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        statusButton = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setBackgroundColor(COLOR_TILE)
            minWidth = dp(220)
            minHeight = dp(64)
        }
        statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(statusTitle)
            addView(statusMessage)
            addView(statusButton)
        }
        root.addView(
            statusPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Identity, top left, faint (iPad parity).
        identityView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_TEXT_FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        root.addView(
            identityView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { setMargins(dp(16), dp(12), 0, 0) }
        )

        // Operator entry, top right, deliberately quiet.
        val operatorButton = TextView(this).apply {
            text = "Operator"
            setTextColor(COLOR_TEXT_FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { promptOperatorPin() }
        }
        root.addView(
            operatorButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, dp(4), dp(8), 0) }
        )

        setContentView(root)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
