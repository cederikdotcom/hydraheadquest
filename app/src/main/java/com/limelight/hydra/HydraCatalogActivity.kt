package com.limelight.hydra

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.limelight.BuildConfig
import com.limelight.PcView
import com.limelight.hydra.model.Experience
import kotlin.concurrent.thread

/**
 * The kiosk self-service catalog, parity with the iPad ExperienceGridView.
 *
 * A dark full-screen grid of experience cards with big touch targets for
 * the controller pointer. Tapping a card starts that experience as
 * self-service through HydraState (discovery, pairing, stream launch all
 * happen in the state machine and HydraStreamHooks). The activity renders
 * whatever state the machine is in: grid, starting overlay, streaming
 * overlay with a stop button, or an error with a dismiss button. When the
 * stream ends the machine returns to selfService and the grid reappears.
 *
 * The app renders as a flat panel at a distance in VR, so everything is
 * sized for that: 26 sp card labels, 64 dp buttons, full-panel operator
 * views instead of AlertDialogs (which render tiny in the headset). All
 * operator surfaces (PIN pad, menu, diagnostics, WireGuard) are overlays
 * added to the root FrameLayout; shared styling lives in [HydraUi].
 *
 * Operator access (top right) is gated by the shared operator PIN, a
 * deterrent only, same as the iPad. Behind it: a diagnostics view showing
 * the same fields the heartbeat sends, issue reporting to the Hydra
 * tracker, the stock Moonlight UI for debugging, WireGuard, and
 * enrollment reset.
 *
 * All UI is built in code; the Hydra layer adds no layout resources.
 */
class HydraCatalogActivity : Activity(), HydraState.Listener {

    companion object {
        /** Shared operator PIN. A deterrent, not security (iPad parity). */
        private const val OPERATOR_PIN = "1337"

        /** Two columns: bigger targets for the controller pointer. */
        private const val GRID_COLUMNS = 2

        /** startActivityForResult code for the system VPN consent dialog. */
        private const val REQUEST_WIREGUARD_CONSENT = 4720

        private const val PIN_LENGTH = 4
        private const val KEY_BACKSPACE = "⌫"
    }

    private lateinit var hydraState: HydraState

    private lateinit var root: FrameLayout
    private lateinit var gridScroll: ScrollView
    private lateinit var grid: GridLayout
    private lateinit var emptyView: TextView
    private lateinit var statusPanel: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusMessage: TextView
    private lateinit var statusSpinner: ProgressBar
    private lateinit var statusButton: Button
    private lateinit var identityView: TextView

    private var experiences: List<Experience> = emptyList()
    private var catalogLoaded = false
    private var routedToEnrollment = false

    /** The one active operator overlay (PIN, menu, diagnostics, WG, ...). */
    private var overlayView: View? = null

    // WireGuard panel views, set while that overlay is showing.
    private var wgStatusView: TextView? = null
    private var wgHandshakeView: TextView? = null
    private var wgMessageView: TextView? = null

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

    override fun onBackPressed() {
        if (overlayView != null) {
            dismissOverlay()
            return
        }
        super.onBackPressed()
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
            statusSpinner.visibility = View.GONE
            statusButton.visibility = View.VISIBLE
            statusButton.text = buttonLabel
            statusButton.setOnClickListener { buttonAction?.invoke() }
        } else {
            // No action to take: discovering or pairing, show the spinner.
            statusSpinner.visibility = View.VISIBLE
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

    /** A big rounded card: 180 dp min height, 26 sp label, pressed state. */
    private fun makeTile(experience: Experience): View {
        val label = TextView(this).apply {
            text = experience.label
            setTextColor(HydraUi.COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_TILE))
            typeface = Typeface.DEFAULT_BOLD
        }
        val details = mutableListOf<String>()
        details.add(if (experience.isPortrait) "Portrait" else "Landscape")
        if (experience.enableMicrophone) {
            details.add("Microphone")
        }
        val caption = TextView(this).apply {
            text = details.joinToString("  ·  ")
            setTextColor(HydraUi.COLOR_TEXT_FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_CAPTION))
            setPadding(0, dp(8), 0, 0)
        }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = HydraUi.pressableCard(this@HydraCatalogActivity)
            minimumHeight = dp(180)
            setPadding(dp(HydraUi.SPACE_M), dp(HydraUi.SPACE_M), dp(HydraUi.SPACE_M), dp(HydraUi.SPACE_M))
            isClickable = true
            isFocusable = true
            addView(label)
            addView(caption)
            setOnClickListener { hydraState.onExperienceTapped(experience) }
        }
        val params = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(dp(12), dp(12), dp(12), dp(12))
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
        identityView.text = listOf(name, location, "v" + BuildConfig.VERSION_NAME)
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    // ------------------------------------------------------------------
    // Overlay scaffolding (full-panel operator views, no AlertDialogs)
    // ------------------------------------------------------------------

    /**
     * A full-screen scrim with a centered rounded panel. Returns the panel
     * column to fill with content. The scrim swallows taps so the grid
     * underneath stays inert.
     */
    private fun showOverlayPanel(widthDp: Int): LinearLayout {
        dismissOverlay()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = HydraUi.card(
                this@HydraCatalogActivity, HydraUi.COLOR_PANEL
            )
            setPadding(dp(HydraUi.SPACE_L), dp(HydraUi.SPACE_L), dp(HydraUi.SPACE_L), dp(HydraUi.SPACE_L))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(HydraUi.COLOR_OVERLAY_SCRIM)
            isClickable = true
            isFocusable = true
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    dp(widthDp),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ).apply {
                    topMargin = dp(HydraUi.SPACE_L)
                    bottomMargin = dp(HydraUi.SPACE_L)
                }
            )
        }
        overlayView = scrim
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return column
    }

    private fun dismissOverlay() {
        overlayView?.let { root.removeView(it) }
        overlayView = null
        wgStatusView = null
        wgHandshakeView = null
        wgMessageView = null
    }

    /** A full-width big button with panel spacing above it. */
    private fun panelButton(
        column: LinearLayout,
        label: String,
        primary: Boolean = false,
        onClick: () -> Unit
    ): Button {
        val button = HydraUi.bigButton(this, label, primary, onClick)
        column.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(HydraUi.SPACE_S) }
        )
        return button
    }

    // ------------------------------------------------------------------
    // Operator access: PIN pad, menu, diagnostics, issue report, reset
    // ------------------------------------------------------------------

    /** Full-panel PIN pad with big keys (iPad OperatorPinView parity). */
    private fun showOperatorPin() {
        val column = showOverlayPanel(460)
        column.gravity = Gravity.CENTER_HORIZONTAL

        column.addView(HydraUi.title(this, "Operator access"))
        val hint = HydraUi.body(this, "Enter the operator PIN")
        column.addView(
            hint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        // Filled/empty dots rather than digits (shoulder-surfing).
        var entered = ""
        val dots = mutableListOf<View>()
        val dotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun dotDrawable(filled: Boolean, wrong: Boolean): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (filled) {
                    setColor(if (wrong) HydraUi.COLOR_RED else HydraUi.COLOR_TEXT)
                } else {
                    setColor(0)
                    setStroke(dp(2), HydraUi.COLOR_TEXT_FAINT)
                }
            }
        }
        for (i in 0 until PIN_LENGTH) {
            val dot = View(this).apply { background = dotDrawable(false, false) }
            dots.add(dot)
            dotRow.addView(
                dot,
                LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    setMargins(dp(12), 0, dp(12), 0)
                }
            )
        }
        column.addView(
            dotRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(HydraUi.SPACE_M)
                bottomMargin = dp(8)
            }
        )

        val wrongLabel = TextView(this).apply {
            text = "Wrong PIN"
            setTextColor(HydraUi.COLOR_RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_BODY))
            visibility = View.INVISIBLE
        }
        column.addView(wrongLabel)

        fun refreshDots(wrong: Boolean) {
            for (i in dots.indices) {
                dots[i].background = dotDrawable(i < entered.length, wrong)
            }
            wrongLabel.visibility = if (wrong) View.VISIBLE else View.INVISIBLE
        }

        fun press(key: String) {
            if (key == KEY_BACKSPACE) {
                if (entered.isNotEmpty()) entered = entered.dropLast(1)
                refreshDots(false)
                return
            }
            if (entered.length >= PIN_LENGTH) return
            entered += key
            if (entered.length < PIN_LENGTH) {
                refreshDots(false)
                return
            }
            if (entered == OPERATOR_PIN) {
                dismissOverlay()
                showOperatorMenu()
            } else {
                entered = ""
                refreshDots(true)
            }
        }

        // Big keypad: 3 columns, 90x72 dp keys, 26 sp digits.
        val keypad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val keyRows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", KEY_BACKSPACE)
        )
        for (rowKeys in keyRows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (key in rowKeys) {
                val cell: View = if (key.isEmpty()) {
                    View(this)
                } else {
                    TextView(this).apply {
                        text = key
                        gravity = Gravity.CENTER
                        setTextColor(HydraUi.COLOR_TEXT)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_TILE))
                        background = HydraUi.pressableCard(
                            this@HydraCatalogActivity,
                            radiusDp = HydraUi.RADIUS_BUTTON
                        )
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { press(key) }
                    }
                }
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(dp(90), dp(72)).apply {
                        setMargins(dp(6), dp(6), dp(6), dp(6))
                    }
                )
            }
            keypad.addView(row)
        }
        column.addView(
            keypad,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(HydraUi.SPACE_S) }
        )

        panelButton(column, "Cancel") { dismissOverlay() }
    }

    /** The operator menu: a list of big buttons, already behind the PIN. */
    private fun showOperatorMenu() {
        val column = showOverlayPanel(520)
        column.addView(HydraUi.title(this, "Operator"))
        column.addView(
            HydraUi.body(this, "Head maintenance and debugging."),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(HydraUi.SPACE_S)
            }
        )
        panelButton(column, "Diagnostics") { showDiagnostics() }
        panelButton(column, "WireGuard") { showWireGuardPanel() }
        panelButton(column, "Moonlight debug UI") {
            dismissOverlay()
            startActivity(Intent(this, PcView::class.java))
        }
        panelButton(column, "Open enrollment screen") {
            dismissOverlay()
            startActivity(Intent(this, HydraEnrollmentActivity::class.java))
        }
        panelButton(column, "Reset enrollment") { confirmReset() }
        panelButton(column, "Close", primary = true) { dismissOverlay() }
    }

    /** Two-column key/value diagnostics panel with big readable text. */
    private fun showDiagnostics() {
        val column = showOverlayPanel(720)
        column.addView(HydraUi.title(this, "Diagnostics"))

        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = HydraUi.card(this@HydraCatalogActivity, HydraUi.COLOR_SURFACE)
            setPadding(dp(HydraUi.SPACE_M), dp(HydraUi.SPACE_S), dp(HydraUi.SPACE_M), dp(HydraUi.SPACE_S))
        }
        val loading = HydraUi.body(this, "Collecting diagnostics...")
        table.addView(loading)
        column.addView(
            table,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(HydraUi.SPACE_M) }
        )

        // Collect off the main thread: the latency probe blocks up to 5 s.
        var lastReport = "Collecting diagnostics..."
        val shownOverlay = overlayView
        thread(name = "HydraDiag") {
            val pairs = diagnosticsPairs()
            lastReport = pairs.joinToString("\n") { "${it.first}: ${it.second}" }
            runOnUiThread {
                if (overlayView !== shownOverlay) return@runOnUiThread
                table.removeAllViews()
                for ((key, value) in pairs) {
                    table.addView(makeDiagnosticsRow(key, value))
                }
            }
        }

        panelButton(column, "Report issue") { fileIssue(lastReport) }
        panelButton(column, "Back") { showOperatorMenu() }
        panelButton(column, "Close", primary = true) { dismissOverlay() }
    }

    private fun makeDiagnosticsRow(key: String, value: String): View {
        val keyView = TextView(this).apply {
            text = key
            setTextColor(HydraUi.COLOR_TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_BODY))
        }
        val valueView = TextView(this).apply {
            text = value
            typeface = Typeface.MONOSPACE
            setTextColor(HydraUi.COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_BODY))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            addView(
                keyView,
                LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(
                valueView,
                LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            )
        }
    }

    /** The same fields the heartbeat sends, plus identity, for operators. */
    private fun diagnosticsPairs(): List<Pair<String, String>> {
        val d = hydraState.collectDiagnostics()
        val config = hydraState.currentHeadConfig()
        return listOf(
            "head_id" to (hydraState.currentHeadId() ?: "-"),
            "name" to (config?.name ?: "-"),
            "district" to (config?.district ?: "-"),
            "venue" to (config?.venue ?: "-"),
            "state" to hydraState.state.javaClass.simpleName,
            "version" to "${d.version}",
            "wireguard" to "${d.wireguard}",
            "app" to "kiosk",
            "routing" to "${d.routing}",
            "latency_ms" to "${d.latencyMs}",
            "wifi_ssid" to "${d.wifiSsid}",
            "local_ip" to "${d.localIp}",
            "moonlight_client_id" to (d.moonlightClientId ?: "-"),
            "stream_host" to (config?.streamHost ?: "-"),
            "stream_app_id" to (config?.stream?.streamAppId ?: "-")
        )
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

    // ------------------------------------------------------------------
    // WireGuard panel (issue #544 Phase 2)
    // ------------------------------------------------------------------

    /**
     * Full-panel WireGuard view: colored status, handshake age, and big
     * Connect / Refresh config / Back buttons. The underlying flow is
     * unchanged: fetch the config through HydraState when none is stored,
     * ask for the one-time system VPN consent when needed
     * (startActivityForResult 4720), then bring the tunnel up in the
     * background and show the resulting status.
     */
    private fun showWireGuardPanel() {
        val column = showOverlayPanel(560)
        column.addView(HydraUi.title(this, "WireGuard tunnel"))

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val statusText = TextView(this).apply {
            text = "Checking status..."
            setTextColor(HydraUi.COLOR_TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_TILE))
            typeface = Typeface.DEFAULT_BOLD
        }
        statusRow.addView(statusText)
        column.addView(
            statusRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(HydraUi.SPACE_M) }
        )

        val handshakeText = TextView(this).apply {
            text = ""
            typeface = Typeface.MONOSPACE
            setTextColor(HydraUi.COLOR_TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_BODY))
            setPadding(0, dp(8), 0, 0)
        }
        column.addView(handshakeText)

        val messageText = TextView(this).apply {
            text = ""
            setTextColor(HydraUi.COLOR_TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_BODY))
            background = HydraUi.card(
                this@HydraCatalogActivity, HydraUi.COLOR_SURFACE
            )
            setPadding(dp(HydraUi.SPACE_S), dp(HydraUi.SPACE_S), dp(HydraUi.SPACE_S), dp(HydraUi.SPACE_S))
            visibility = View.GONE
        }
        column.addView(
            messageText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(HydraUi.SPACE_S) }
        )

        wgStatusView = statusText
        wgHandshakeView = handshakeText
        wgMessageView = messageText

        panelButton(column, "Connect", primary = true) { startWireGuardConnect() }
        panelButton(column, "Refresh config") { refreshWireGuardConfig() }
        panelButton(column, "Back") { showOperatorMenu() }

        refreshWireGuardStatus()
    }

    /** Heartbeat status strings mapped to VR-readable colors. */
    private fun wireGuardStatusColor(status: String): Int = when {
        status.startsWith("up") -> HydraUi.COLOR_GREEN
        status == "consent-needed" -> HydraUi.COLOR_ORANGE
        status.startsWith("error") -> HydraUi.COLOR_RED
        status == "disabled" -> HydraUi.COLOR_TEXT_DIM
        else -> HydraUi.COLOR_ORANGE
    }

    /** Background: read the tunnel status and paint the panel. */
    private fun refreshWireGuardStatus() {
        val wireGuard = HydraApp.from(this).hydraWireGuard
        thread(name = "HydraWgStatus") {
            val status = wireGuard.statusString()
            val handshake = wireGuard.lastHandshakeDescription()
            runOnUiThread {
                wgStatusView?.apply {
                    text = status
                    setTextColor(wireGuardStatusColor(status))
                }
                wgHandshakeView?.text = handshake
            }
        }
    }

    /** Panel message area; Toast fallback when the panel is gone. */
    private fun showWireGuardMessage(message: String) {
        val view = wgMessageView
        if (view != null) {
            view.text = message
            view.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Fetch the config when none is stored, ask for the one-time system
     * VPN consent when needed, then bring the tunnel up and show status.
     */
    private fun startWireGuardConnect() {
        val wireGuard = HydraApp.from(this).hydraWireGuard
        showWireGuardMessage("Working...")
        thread(name = "HydraWgAction") {
            if (!wireGuard.hasStoredConfig()) {
                val configText = try {
                    hydraState.fetchWireguardConfig()
                } catch (e: Exception) {
                    val message = e.message ?: "fetch failed"
                    val display = if (
                        message.contains("HTTP 404") ||
                        message.contains("not provisioned")
                    ) {
                        "Not provisioned. An admin must provision this " +
                            "head on hydraguard, then retry."
                    } else {
                        // Never echo config content; this is an error only.
                        "Config fetch failed: $message"
                    }
                    runOnUiThread { showWireGuardMessage(display) }
                    return@thread
                }
                try {
                    wireGuard.storeConfig(configText)
                } catch (e: Exception) {
                    runOnUiThread {
                        showWireGuardMessage("Config store failed: ${e.message}")
                    }
                    return@thread
                }
            }
            val consent = wireGuard.prepareIntent()
            if (consent != null) {
                runOnUiThread {
                    startActivityForResult(consent, REQUEST_WIREGUARD_CONSENT)
                }
            } else {
                connectWireGuardAndReport(wireGuard)
            }
        }
    }

    /** Deliberate re-fetch of the stored config from the cluster. */
    private fun refreshWireGuardConfig() {
        val wireGuard = HydraApp.from(this).hydraWireGuard
        showWireGuardMessage("Fetching a fresh configuration...")
        thread(name = "HydraWgAction") {
            val configText = try {
                hydraState.fetchWireguardConfig()
            } catch (e: Exception) {
                val message = e.message ?: "fetch failed"
                val display = if (
                    message.contains("HTTP 404") ||
                    message.contains("not provisioned")
                ) {
                    "Not provisioned. An admin must provision this " +
                        "head on hydraguard, then retry."
                } else {
                    // Never echo config content; this is an error only.
                    "Config fetch failed: $message"
                }
                runOnUiThread { showWireGuardMessage(display) }
                return@thread
            }
            try {
                wireGuard.storeConfig(configText)
            } catch (e: Exception) {
                runOnUiThread {
                    showWireGuardMessage("Config store failed: ${e.message}")
                }
                return@thread
            }
            runOnUiThread {
                showWireGuardMessage(
                    "Configuration refreshed. Connect to apply it."
                )
            }
            refreshWireGuardStatus()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_WIREGUARD_CONSENT) return
        if (resultCode == RESULT_OK) {
            val wireGuard = HydraApp.from(this).hydraWireGuard
            thread(name = "HydraWgAction") {
                connectWireGuardAndReport(wireGuard)
            }
        } else {
            showWireGuardMessage(
                "VPN consent was denied. The tunnel stays down until an " +
                    "operator runs the WireGuard action again and accepts."
            )
        }
    }

    /** Background: bring the tunnel up, wait a moment, report status. */
    private fun connectWireGuardAndReport(wireGuard: HydraWireGuard) {
        wireGuard.bringUpStored()
        // Give the first handshake a moment so the status is meaningful.
        try {
            Thread.sleep(2000)
        } catch (ignored: InterruptedException) {
        }
        val status = wireGuard.statusString()
        val handshake = wireGuard.lastHandshakeDescription()
        runOnUiThread {
            val statusView = wgStatusView
            if (statusView != null) {
                statusView.text = status
                statusView.setTextColor(wireGuardStatusColor(status))
                wgHandshakeView?.text = handshake
                wgMessageView?.visibility = View.GONE
            } else {
                // Panel gone (consent round trip): report the old way.
                Toast.makeText(
                    this, "WireGuard: $status, $handshake", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmReset() {
        val column = showOverlayPanel(560)
        column.addView(HydraUi.title(this, "Reset enrollment"))
        column.addView(
            HydraUi.body(
                this,
                "This forgets the head identity and stops the kiosk until " +
                    "the head is enrolled again. Continue?"
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(HydraUi.SPACE_S)
                bottomMargin = dp(HydraUi.SPACE_S)
            }
        )
        panelButton(column, "Reset") {
            dismissOverlay()
            // reset() moves the machine to unconfigured; render() then
            // routes to the enrollment screen.
            hydraState.reset()
        }
        panelButton(column, "Cancel", primary = true) { showOperatorMenu() }
    }

    // ------------------------------------------------------------------
    // View construction
    // ------------------------------------------------------------------

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(HydraUi.COLOR_BACKGROUND)
        }

        // Experience grid.
        grid = GridLayout(this).apply {
            columnCount = GRID_COLUMNS
            useDefaultMargins = false
        }
        emptyView = TextView(this).apply {
            setTextColor(HydraUi.COLOR_TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_BUTTON))
            gravity = Gravity.CENTER
            setPadding(dp(HydraUi.SPACE_M), dp(120), dp(HydraUi.SPACE_M), dp(HydraUi.SPACE_M))
        }
        val gridColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(HydraUi.SPACE_L), dp(96), dp(HydraUi.SPACE_L), dp(HydraUi.SPACE_L))
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
            setTextColor(HydraUi.COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_STATUS))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        statusMessage = TextView(this).apply {
            setTextColor(HydraUi.COLOR_TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_BUTTON))
            gravity = Gravity.CENTER
            setPadding(dp(HydraUi.SPACE_M), dp(HydraUi.SPACE_S), dp(HydraUi.SPACE_M), dp(HydraUi.SPACE_M))
        }
        statusSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        statusButton = HydraUi.bigButton(this, "", primary = true) {}
        statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(statusTitle)
            addView(statusMessage)
            addView(
                statusSpinner,
                LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(HydraUi.SPACE_S)
                }
            )
            addView(
                statusButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(HydraUi.SPACE_S)
                }
            )
        }
        root.addView(
            statusPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Identity, top left, readable at panel distance (iPad parity).
        identityView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(HydraUi.COLOR_TEXT_FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_BODY))
        }
        root.addView(
            identityView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { setMargins(dp(HydraUi.SPACE_M), dp(HydraUi.SPACE_S), 0, 0) }
        )

        // Operator entry, top right, quiet but big enough to point at.
        val operatorButton = TextView(this).apply {
            text = "Operator"
            setTextColor(HydraUi.COLOR_TEXT_FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HydraUi.sp(context, HydraUi.TEXT_BODY))
            setPadding(dp(HydraUi.SPACE_M), dp(HydraUi.SPACE_S), dp(HydraUi.SPACE_M), dp(HydraUi.SPACE_S))
            isClickable = true
            isFocusable = true
            setOnClickListener { showOperatorPin() }
        }
        root.addView(
            operatorButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, dp(8), dp(8), 0) }
        )

        setContentView(root)
    }

    private fun dp(value: Int): Int {
        return HydraUi.dp(this, value)
    }
}
