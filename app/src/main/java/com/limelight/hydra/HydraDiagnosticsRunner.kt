package com.limelight.hydra

import android.content.Context
import android.util.Log
import com.limelight.hydra.model.EligibleBody
import com.limelight.hydra.model.HeadConfig
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sequenced operator diagnostics run, ported from the iPad app's
 * DiagnosticsRunner (hydraheadipad DiagnosticsView.swift).
 *
 * Five steps, same order and semantics as the iPad:
 *
 * 1. Cluster connection: GET /api/v1/heads/{id} with this head's token.
 *    Passes with the district / venue label from the response.
 * 2. Experience catalog: GET /api/v1/heads/{id}/experiences. An empty
 *    catalog is a failure (the venue has nothing to show).
 * 3. Body available: GET /api/v1/bodies/eligible for this head with an
 *    empty experience filter. Passes on the first returned body.
 * 4. Body reachable: TCP connect to the body on port 47990. Candidates
 *    are probed in the same order HydraState streams from (LAN address
 *    first, then the WireGuard address), using EligibleBody.candidateHosts
 *    and the HydraState probe constants, so the diagnosis matches what a
 *    real stream start would do.
 * 5. WireGuard routing: TCP probe of the mesh path. Target is the body's
 *    WireGuard address when it was not already the host step 4 chose,
 *    else the mesh hub gateway 10.10.0.1 on port 80 (iPad rule). The
 *    detail line adds the local tunnel status from HydraWireGuard, which
 *    the iPad does not have in-process.
 *
 * A step can be pending, running, passed, failed, or skipped. Steps run
 * on one background thread; [listener] is called from that thread and
 * must hop to the main thread itself. The last finished run's summary is
 * kept for the operator issue report.
 *
 * All network calls are blocking and bounded: the cluster calls by the
 * HydraClusterClient 15 s timeout, the probes by their explicit caps.
 */
class HydraDiagnosticsRunner(context: Context) {

    enum class Status { PENDING, RUNNING, PASSED, FAILED, SKIPPED }

    data class Step(
        val label: String,
        val status: Status = Status.PENDING,
        val detail: String = ""
    )

    /** Observer for step updates. Called on the runner thread. */
    interface Listener {
        fun onStepsChanged(steps: List<Step>)
        fun onRunFinished(steps: List<Step>)
    }

    companion object {
        private const val TAG = "HydraDiagnostics"

        /** The five iPad step labels, in run order. */
        val STEP_LABELS = listOf(
            "Cluster connection",
            "Experience catalog",
            "Body available",
            "Body reachable",
            "WireGuard routing"
        )

        /** Mesh hub gateway, the iPad's fallback WireGuard probe target. */
        const val WG_HUB_IP = "10.10.0.1"

        /** The hub answers HTTP, so the gateway probe uses port 80. */
        const val WG_HUB_PORT = 80

        /** WireGuard routing probe timeout (iPad uses 4 s here). */
        const val WG_PROBE_TIMEOUT_MS = 4000

        /** Short pause between steps so the run reads as a sequence. */
        private const val STEP_PAUSE_MS = 120L
    }

    private val appContext: Context = context.applicationContext

    @Volatile
    var listener: Listener? = null

    private val running = AtomicBoolean(false)

    @Volatile
    private var steps: List<Step> = STEP_LABELS.map { Step(it) }

    /** Issue-report summary of the last FINISHED run, or null. */
    @Volatile
    private var lastSummary: String? = null

    /** The current step list. Safe to read from any thread. */
    fun snapshot(): List<Step> = steps

    fun isRunning(): Boolean = running.get()

    /**
     * The last finished run as issue-report text, one line per step in
     * the iPad format: "[OK] Cluster connection: bxl1 / cloud-seven".
     * Null before the first completed run.
     */
    fun summaryOrNull(): String? = lastSummary

    /**
     * Start a run on a background thread. Returns false when a run is
     * already in progress (the new request is ignored, iPad parity: the
     * run button is disabled while running).
     */
    fun run(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        Thread({
            try {
                runAll()
            } catch (t: Throwable) {
                // A diagnostics run must never take the kiosk down.
                Log.w(TAG, "diagnostics run failed: ${t.message}")
            } finally {
                running.set(false)
                lastSummary = summarize(steps)
                listener?.onRunFinished(steps)
            }
        }, "HydraDiagnostics").start()
        return true
    }

    // ------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------

    private fun runAll() {
        steps = STEP_LABELS.map { Step(it) }
        listener?.onStepsChanged(steps)

        val enrollment = HydraConfigStore(appContext).load()
        if (enrollment == null) {
            mark(0, Status.FAILED, "Head is not enrolled")
            for (i in 1 until steps.size) {
                mark(i, Status.SKIPPED, "Not enrolled")
            }
            return
        }
        val client = HydraClusterClient(enrollment)

        // 1. Cluster connection
        mark(0, Status.RUNNING)
        var config: HeadConfig? = null
        try {
            config = client.getHeadConfig()
            val label = listOfNotNull(config.district, config.venue)
                .filter { it.isNotEmpty() }
                .joinToString(" / ")
            mark(0, Status.PASSED, if (label.isEmpty()) "Connected" else label)
        } catch (e: IOException) {
            mark(0, Status.FAILED, e.message ?: "Request failed")
        }
        pause()

        val venueLabel = listOfNotNull(config?.district, config?.venue)
            .filter { it.isNotEmpty() }
            .joinToString(" / ")
            .ifEmpty { "this head" }

        // 2. Experience catalog
        mark(1, Status.RUNNING)
        try {
            val list = client.getExperiences()
            if (list.isEmpty()) {
                mark(1, Status.FAILED, "No experiences for $venueLabel")
            } else {
                val names = list.joinToString(", ") { it.name }
                val noun = if (list.size == 1) "experience" else "experiences"
                mark(1, Status.PASSED, "${list.size} $noun: $names")
            }
        } catch (e: IOException) {
            mark(1, Status.FAILED, e.message ?: "Request failed")
        }
        pause()

        // 3. Body available (empty experience filter, iPad parity)
        mark(2, Status.RUNNING)
        var body: EligibleBody? = null
        try {
            val bodies = client.getEligibleBodies(
                district = config?.district,
                venue = config?.venue,
                headId = enrollment.headId,
                experience = ""
            )
            val first = bodies.firstOrNull()
            if (first == null) {
                mark(2, Status.FAILED, "No body online for $venueLabel")
            } else {
                body = first
                val hosts = first.candidateHosts()
                val hostLabel = if (hosts.isEmpty()) "" else " (${hosts.joinToString(", ")})"
                mark(2, Status.PASSED, (first.name ?: first.id ?: "unknown") + hostLabel)
            }
        } catch (e: IOException) {
            mark(2, Status.FAILED, e.message ?: "Request failed")
        }
        pause()

        // 4. Body reachable: TCP 47990, LAN first then mesh, the same
        //    candidate order and timeouts a stream start uses.
        var chosenHost: String? = null
        val candidates = body?.candidateHosts() ?: emptyList()
        if (body == null) {
            mark(3, Status.SKIPPED, "No body to probe")
        } else if (candidates.isEmpty()) {
            mark(3, Status.FAILED, "Body has no reachable IP")
        } else {
            mark(3, Status.RUNNING)
            var result: Pair<String, Long>? = null
            for (candidate in candidates) {
                val ms = probeTcp(
                    candidate, HydraState.SUNSHINE_API_PORT, HydraState.PROBE_TIMEOUT_MS
                )
                if (ms != null) {
                    result = candidate to ms
                    break
                }
            }
            if (result == null) {
                // One patient retry on the first candidate, matching the
                // 5 s cap of the heartbeat latency probe and the iPad's
                // 5 s step timeout.
                val first = candidates.first()
                probeTcp(first, HydraState.SUNSHINE_API_PORT, HydraState.LATENCY_CAP_MS)
                    ?.let { result = first to it }
            }
            val reached = result
            if (reached != null) {
                chosenHost = reached.first
                mark(
                    3, Status.PASSED,
                    "${reached.first}:${HydraState.SUNSHINE_API_PORT} open, ${reached.second} ms"
                )
            } else {
                mark(
                    3, Status.FAILED,
                    "Cannot reach ${candidates.joinToString(", ")} on port " +
                        "${HydraState.SUNSHINE_API_PORT}. Check routing."
                )
            }
        }
        pause()

        // 5. WireGuard routing: probe the body's WG IP when step 4 did not
        //    already choose it, else the mesh hub gateway (iPad rule).
        mark(4, Status.RUNNING)
        val wgIp = body?.wireguardIp?.takeIf { it.isNotEmpty() }
        val target: String
        val port: Int
        if (wgIp != null && wgIp != chosenHost) {
            target = wgIp
            port = HydraState.SUNSHINE_API_PORT
        } else if (chosenHost != null && chosenHost.startsWith("10.10.")) {
            target = chosenHost
            port = HydraState.SUNSHINE_API_PORT
        } else {
            target = WG_HUB_IP
            port = WG_HUB_PORT
        }
        val tunnel = tunnelStatus()
        val ms = probeTcp(target, port, WG_PROBE_TIMEOUT_MS)
        if (ms != null) {
            mark(4, Status.PASSED, "$target reachable, $ms ms (tunnel $tunnel)")
        } else {
            mark(4, Status.FAILED, "Cannot reach $target. No WireGuard route (tunnel $tunnel).")
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Local tunnel status for the step 5 detail line. Never throws. */
    private fun tunnelStatus(): String {
        return try {
            (appContext as? HydraApp)?.hydraWireGuard?.statusString() ?: "unknown"
        } catch (t: Throwable) {
            "unknown"
        }
    }

    /**
     * TCP connect probe, same shape as HydraState.probeTcp (private
     * there). Returns the RTT in ms, or null when unreachable.
     */
    private fun probeTcp(host: String, port: Int, timeoutMs: Int): Long? {
        return try {
            val started = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (System.nanoTime() - started) / 1_000_000
        } catch (e: Exception) {
            null
        }
    }

    private fun mark(index: Int, status: Status, detail: String = "") {
        val updated = steps.toMutableList()
        if (index !in updated.indices) return
        updated[index] = updated[index].copy(status = status, detail = detail)
        steps = updated
        listener?.onStepsChanged(updated)
    }

    private fun pause() {
        try {
            Thread.sleep(STEP_PAUSE_MS)
        } catch (ignored: InterruptedException) {
        }
    }

    /** Issue-report lines, iPad format: "[OK] label: detail". */
    private fun summarize(steps: List<Step>): String {
        return steps.joinToString("\n") { step ->
            val icon = when (step.status) {
                Status.PASSED -> "OK"
                Status.FAILED -> "FAIL"
                Status.SKIPPED -> "SKIP"
                else -> "?"
            }
            val detail = if (step.detail.isEmpty()) "" else ": ${step.detail}"
            "[$icon] ${step.label}$detail"
        }
    }
}
