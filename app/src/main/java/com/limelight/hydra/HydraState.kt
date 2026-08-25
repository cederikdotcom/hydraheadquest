package com.limelight.hydra

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.limelight.BuildConfig
import com.limelight.hydra.model.EligibleBody
import com.limelight.hydra.model.EnrollmentConfig
import com.limelight.hydra.model.Experience
import com.limelight.hydra.model.HeadConfig
import com.limelight.hydra.model.HeadDiagnostics
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The head state machine, ported from the iPad app's AppState.swift.
 *
 * States and transitions follow docs/hydra-api-contract.md:
 * - enroll -> selfService([]) + 30 s config tick + 3 s command poll
 * - reset -> unconfigured
 * - tap or active assignment -> discovering -> pairing -> streaming
 * - streaming + changed assignment -> stop then restart
 * - user stop -> selfService (await the DELETE first)
 * - moonlight disconnect -> error("Session interrupted") + DELETE fire and forget
 * - error dismiss -> selfService
 * - every tick fetches config, refreshes the catalog, and heartbeats;
 *   only the assignment handling step is a no-op in discovering, pairing,
 *   error, and unconfigured
 *
 * All work runs on a single-thread scheduler; UI observers get callbacks
 * through [listener] on that thread and must hop to the main thread themselves.
 */
class HydraState(context: Context, private val store: HydraConfigStore) {

    companion object {
        private const val TAG = "HydraState"

        /** Heartbeat and config tick interval when not streaming. */
        const val IDLE_TICK_SECONDS = 30L

        /** Heartbeat and config tick interval while streaming. */
        const val STREAMING_TICK_SECONDS = 5L

        /** Command poll interval. */
        const val COMMAND_POLL_SECONDS = 3L

        /** Sunshine web UI / API port, also the reachability probe target. */
        const val SUNSHINE_API_PORT = 47990

        /** TCP probe timeout per body candidate host. */
        const val PROBE_TIMEOUT_MS = 1000

        /** Latency measurement cap for diagnostics. */
        const val LATENCY_CAP_MS = 5000

        /** Delay before posting the PIN, so /pair getservercert is pending. */
        const val PIN_POST_DELAY_MS = 300L

        /** Timeout for the Sunshine PIN post. */
        const val PIN_POST_TIMEOUT_MS = 20000
    }

    /** Head states, mirroring AppState.swift. */
    sealed class State {
        object Unconfigured : State()
        object Idle : State()
        data class SelfService(val experiences: List<Experience>) : State()
        data class Discovering(val experience: Experience) : State()
        data class Pairing(
            val bodyName: String,
            val host: String,
            val experience: Experience
        ) : State()

        data class Streaming(
            val host: String,
            val experience: Experience,
            val bodyId: String,
            val serverCert: X509Certificate?,
            /**
             * True when the user tapped the grid. Informational: like the
             * iPad app, the tick decides by assignment presence, not by
             * this flag. A stream with no active server assignment is
             * left alone; an active assignment always wins.
             */
            val selfService: Boolean
        ) : State()

        data class Error(val message: String) : State()
    }

    /** Observer for state changes. Called on the scheduler thread. */
    interface Listener {
        fun onStateChanged(state: State)
        fun onCatalogUpdated(experiences: List<Experience>)
    }

    /**
     * Integration surface toward the Moonlight streaming stack.
     *
     * Implemented by [HydraStreamHooks] against the real Moonlight core:
     * NvHTTP pairing on http://host:47989 (HTTPS 47984 discovered from
     * /serverinfo, never 47990), PairingManager with the PIN posted to
     * Sunshine via [postPinToSunshine], app list lookup, and a Game or
     * GameXR activity launch. HydraApp wires the instance in at startup.
     */
    interface StreamHooks {
        /**
         * Launch pairing plus streaming toward the selected body.
         * Pass [selfService] back into [onStreamEstablished] unchanged.
         */
        fun launchStream(
            host: String,
            experience: Experience,
            headConfig: HeadConfig,
            bodyId: String,
            selfService: Boolean
        )

        /** Tear down the active NvConnection (NvConnection.stop()). */
        fun stopStream()
    }

    private val appContext: Context = context.applicationContext
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "HydraState").apply { isDaemon = true }
    }

    @Volatile
    var state: State = State.Unconfigured
        private set

    @Volatile
    var listener: Listener? = null

    @Volatile
    var streamHooks: StreamHooks? = null

    /**
     * Screenshot provider for the remote screenshot command. Returns JPEG
     * bytes (quality 70) or null when capture is not possible. HydraApp
     * wires this to [HydraScreenshot] (PixelCopy of the current activity).
     *
     * TODO(#544): the streaming video surface may capture black on Quest,
     * same as the Metal capture issue on iPad. MediaProjection may be
     * needed for stream frames. The wire contract is unaffected.
     */
    @Volatile
    var screenshotProvider: (() -> ByteArray?)? = null

    /**
     * Supplies the Moonlight unique client id for heartbeat diagnostics.
     * HydraApp wires this to the IdentityManager uid.
     */
    @Volatile
    var moonlightClientIdProvider: (() -> String?)? = null

    /**
     * Supplies the live WireGuard tunnel status for heartbeat diagnostics.
     * HydraApp wires this to HydraWireGuard.statusString(). "disabled"
     * before wiring, matching the no-config state.
     */
    @Volatile
    var wireguardStatusProvider: (() -> String)? = null

    // Volatile: written on the executor, also read by the operator UI
    // thread (fetchWireguardConfig).
    @Volatile
    private var client: HydraClusterClient? = null

    @Volatile
    private var enrollment: EnrollmentConfig? = null

    @Volatile
    private var cachedConfig: HeadConfig? = null

    @Volatile
    private var cachedCatalog: List<Experience> = emptyList()
    private var tickFuture: ScheduledFuture<*>? = null
    private var commandFuture: ScheduledFuture<*>? = null

    /**
     * All candidate hosts of the body behind the current (or last
     * launched) stream, captured at discovery time. The tick compares the
     * server's stream block against this set, not against the single
     * resolved streamHost: a mesh stream runs on the body's 10.10.x
     * wireguard address while the stream block's stream_url_lan names the
     * body's venue-LAN address, so a single-host compare misreads the
     * head's own session as a changed assignment. Executor-confined.
     */
    private var currentBodyHosts: List<String> = emptyList()

    /** A stream session that ended and must not be relaunched by the tick. */
    private data class EndedSession(
        val bodyId: String,
        val app: String,
        val hosts: List<String>
    )

    /**
     * Set when a self-service stream ends (user stop, disconnect, or
     * failure). hydracluster keeps a stream block for the head's own
     * session (#137) and can still serve it after the DELETE, or forever
     * when the DELETE failed. Without this guard the tick reads that
     * stale block as an operator assignment and relaunches the stream
     * seconds after it ended. While set, a matching assignment is ignored
     * and the DELETE is retried; the marker clears when the block is gone
     * server-side, when the user taps, or when a non-matching (genuinely
     * new) assignment arrives. Executor-confined.
     */
    private var endedSession: EndedSession? = null

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Start the machine. With a stored enrollment the head goes to
     * selfService and the timers start. Without one it stays unconfigured.
     */
    fun start() {
        executor.execute {
            val config = store.load()
            if (config == null) {
                setState(State.Unconfigured)
                return@execute
            }
            adopt(config)
        }
    }

    /**
     * Adopt a stored enrollment when the machine has not adopted one yet.
     * Safe to call from activity onResume: a no-op while enrolled and
     * running, and a plain state refresh while unenrolled. This is how the
     * catalog picks up an enrollment the enrollment activity saved straight
     * to the store.
     */
    fun ensureStarted() {
        executor.execute {
            if (enrollment != null) return@execute
            val config = store.load()
            if (config == null) {
                setState(State.Unconfigured)
            } else {
                adopt(config)
            }
        }
    }

    /** Latest head config from the cluster, or null before the first tick. */
    fun currentHeadConfig(): HeadConfig? = cachedConfig

    /** Latest experience catalog snapshot. */
    fun currentCatalog(): List<Experience> = cachedCatalog

    /** This head's id, or null when unenrolled. */
    fun currentHeadId(): String? = enrollment?.headId

    /**
     * Fetch this head's wg-quick config from the cluster (contract section
     * 12). Explicit operator action only; the stored config is reused on
     * every other path. Blocking; call off the main thread. The returned
     * text contains the private key: never log it.
     */
    @Throws(IOException::class)
    fun fetchWireguardConfig(): String {
        val apiClient = client ?: throw IOException("Head is not enrolled")
        return apiClient.getWireguardConfig()
    }

    /** Adopt a fresh enrollment (called right after enroll succeeds). */
    fun onEnrolled(config: EnrollmentConfig) {
        executor.execute {
            store.save(config)
            adopt(config)
        }
    }

    /** Reset: forget enrollment, stop timers, back to unconfigured. */
    fun reset() {
        executor.execute {
            tickFuture?.cancel(false)
            tickFuture = null
            commandFuture?.cancel(false)
            commandFuture = null
            store.clear()
            enrollment = null
            client = null
            cachedConfig = null
            cachedCatalog = emptyList()
            setState(State.Unconfigured)
        }
    }

    private fun adopt(config: EnrollmentConfig) {
        enrollment = config
        client = HydraClusterClient(config)
        setState(State.SelfService(cachedCatalog))
        scheduleTick(IDLE_TICK_SECONDS)
        scheduleCommandPoll()
        // Run one tick right away so the catalog and config load fast.
        executor.execute { safeTick() }
    }

    // ------------------------------------------------------------------
    // User actions
    // ------------------------------------------------------------------

    /** The user tapped an experience in the grid. */
    fun onExperienceTapped(experience: Experience) {
        executor.execute {
            val current = state
            if (current is State.SelfService || current is State.Idle) {
                // A fresh tap always overrides the ended-session guard.
                endedSession = null
                startExperience(experience, selfService = true)
            }
        }
    }

    /**
     * The user stopped the stream. Await the DELETE before showing the grid,
     * so the body's slot is free when the catalog reappears.
     */
    fun onUserStop() {
        executor.execute {
            val current = state
            if (current !is State.Streaming) return@execute
            markEnded(current)
            streamHooks?.stopStream()
            try {
                client?.deleteStream(current.bodyId)
            } catch (e: IOException) {
                Log.w(TAG, "deleteStream on user stop failed: ${e.message}")
            }
            setState(State.SelfService(cachedCatalog))
            scheduleTick(IDLE_TICK_SECONDS)
        }
    }

    /**
     * Moonlight reported a disconnect. DELETE is fire and forget here.
     * Wire this to com.limelight.nvstream.NvConnectionListener.connectionTerminated.
     */
    fun onStreamInterrupted() {
        executor.execute {
            val current = state
            if (current is State.Streaming) {
                markEnded(current)
                try {
                    client?.deleteStream(current.bodyId)
                } catch (e: IOException) {
                    Log.w(TAG, "deleteStream on interrupt failed: ${e.message}")
                }
            }
            setState(State.Error("Session interrupted. The connection was lost."))
            scheduleTick(IDLE_TICK_SECONDS)
        }
    }

    /**
     * The streaming activity went away without an explicit stop from the
     * catalog: the user exited the stream in Game, or the session ended on
     * its own after frames flowed. Return to the grid. The DELETE frees the
     * body slot; a failure is non-fatal (the body heartbeat self-corrects).
     */
    fun onStreamEnded() {
        executor.execute {
            val current = state
            if (current !is State.Streaming) return@execute
            markEnded(current)
            try {
                client?.deleteStream(current.bodyId)
            } catch (e: IOException) {
                Log.w(TAG, "deleteStream on stream end failed: ${e.message}")
            }
            setState(State.SelfService(cachedCatalog))
            scheduleTick(IDLE_TICK_SECONDS)
        }
    }

    /**
     * Remember a self-service session on its way out, so the tick does
     * not relaunch it from the head's own stale server-side stream block.
     * Operator-assigned sessions are NOT marked: an assignment that
     * persists server-side is meant to restart (that is the assignment
     * contract), and only the head's own self-service record must never
     * act as one.
     */
    private fun markEnded(current: State.Streaming) {
        if (!current.selfService) return
        endedSession = EndedSession(
            bodyId = current.bodyId,
            app = current.experience.name,
            hosts = (currentBodyHosts + current.host).distinct()
        )
    }

    /** Hosts named by the config's stream block: stream_url_lan and stream_url. */
    private fun assignmentHosts(config: HeadConfig): List<String> {
        val s = config.stream ?: return emptyList()
        return listOfNotNull(
            s.streamUrlLan?.takeIf { it.isNotEmpty() }?.let { HeadConfig.stripScheme(it) },
            s.streamUrl?.takeIf { it.isNotEmpty() }?.let { HeadConfig.stripScheme(it) }
        ).distinct()
    }

    /**
     * True when the two host sets can refer to the same body. An empty
     * set cannot disprove anything, so it counts as a match: when in
     * doubt, treat the assignment as unchanged rather than kill and
     * relaunch a running stream.
     */
    private fun hostsOverlap(a: List<String>, b: List<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return true
        return a.any { it in b }
    }

    /**
     * Pairing, app lookup, or the activity launch failed before the stream
     * was established. Called by StreamHooks with a user-readable message.
     */
    fun onStreamFailed(message: String) {
        executor.execute {
            val current = state
            if (current is State.Streaming) {
                markEnded(current)
                try {
                    client?.deleteStream(current.bodyId)
                } catch (e: IOException) {
                    Log.w(TAG, "deleteStream on stream failure failed: ${e.message}")
                }
            }
            setState(State.Error(message))
            scheduleTick(IDLE_TICK_SECONDS)
        }
    }

    /** The stream is up. Called by the integration layer once frames flow. */
    fun onStreamEstablished(
        host: String,
        experience: Experience,
        bodyId: String,
        serverCert: X509Certificate?,
        selfService: Boolean
    ) {
        executor.execute {
            setState(State.Streaming(host, experience, bodyId, serverCert, selfService))
            scheduleTick(STREAMING_TICK_SECONDS)
            // TODO(#544): when experience.enableMicrophone is true, start the
            // mic relay: Opus over RTP to UDP host:47995, 48 kHz mono, 960
            // sample frames, PT=111, RMS noise gate. Phase 2.
        }
    }

    /** The user dismissed the error screen. */
    fun onErrorDismissed() {
        executor.execute {
            if (state is State.Error) {
                setState(State.SelfService(cachedCatalog))
            }
        }
    }

    // ------------------------------------------------------------------
    // Tick (config + heartbeat), every 30 s idle / 5 s streaming
    // ------------------------------------------------------------------

    private fun scheduleTick(intervalSeconds: Long) {
        tickFuture?.cancel(false)
        tickFuture = executor.scheduleWithFixedDelay(
            { safeTick() }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS
        )
    }

    private fun safeTick() {
        try {
            tick()
        } catch (e: Exception) {
            Log.w(TAG, "tick failed: ${e.message}")
        }
    }

    private fun tick() {
        val apiClient = client ?: return

        // 1. Fetch config, fall back to the cache on failure. With no config
        //    at all (fetch failed, nothing cached) the tick ends here,
        //    exactly like the iPad app.
        val config: HeadConfig? = try {
            apiClient.getHeadConfig().also { cachedConfig = it }
        } catch (e: IOException) {
            Log.w(TAG, "config fetch failed, using cache: ${e.message}")
            cachedConfig
        }
        if (config == null) return

        // 2. Refresh the catalog.
        try {
            val catalog = apiClient.getExperiences()
            cachedCatalog = catalog
            if (state is State.SelfService) {
                setState(State.SelfService(catalog))
            }
            listener?.onCatalogUpdated(catalog)
        } catch (e: IOException) {
            Log.w(TAG, "catalog refresh failed: ${e.message}")
        }

        // 3. Assignment handling. A no-op in transitional and terminal
        //    states (discovering, pairing, error); the in-progress flow
        //    finishes, and an errored head waits for a dismiss.
        when (val current = state) {
            is State.Idle, is State.SelfService -> {
                val appId = config.stream?.streamAppId
                if (config.hasActiveAssignment && appId != null) {
                    val ended = endedSession
                    if (ended != null && appId == ended.app &&
                        hostsOverlap(assignmentHosts(config), ended.hosts)
                    ) {
                        // The head's own just-ended session, still stored
                        // server-side as a stream block (#137). Never
                        // relaunch from it; retry the DELETE until the
                        // block clears, then the marker resets below.
                        Log.i(
                            "HydraStream",
                            "ignoring stale stream block for ended session " +
                                "(app=$appId body=${ended.bodyId}); retrying delete"
                        )
                        try {
                            client?.deleteStream(ended.bodyId)
                        } catch (e: IOException) {
                            Log.w(TAG, "stale stream block delete failed: ${e.message}")
                        }
                    } else {
                        endedSession = null
                        val experience = cachedCatalog.firstOrNull { it.name == appId }
                            ?: Experience(appId, appId, null, false)
                        startExperience(experience, selfService = false)
                    }
                } else {
                    // No stream block server-side: any earlier session is
                    // fully cleaned up; a future assignment is genuinely new.
                    endedSession = null
                    // Stay in selfService with the latest catalog.
                    setState(State.SelfService(cachedCatalog))
                }
            }
            is State.Streaming -> {
                if (config.hasActiveAssignment) {
                    // Restart only on a real app or body change. Never
                    // re-pair a stable stream. The body is matched on ALL
                    // of its known hosts, not on the single resolved
                    // streamHost: a mesh stream runs on the body's
                    // 10.10.x address while the stream block's
                    // stream_url_lan names its venue-LAN address, and the
                    // old single-host compare read the head's own session
                    // as a changed assignment, killing and relaunching a
                    // healthy stream on the first 5 s tick that saw the
                    // block (the ~23 s stream deaths with a GameXR
                    // relaunch 4 s later, seen 2026-08-25).
                    val newApp = config.stream?.streamAppId
                    val newHosts = assignmentHosts(config)
                    val knownHosts = (currentBodyHosts + current.host).distinct()
                    val sameApp = newApp == current.experience.name
                    val sameBody = hostsOverlap(newHosts, knownHosts)
                    if (!sameApp || !sameBody) {
                        Log.i(
                            "HydraStream",
                            "assignment changed (app $newApp vs " +
                                "${current.experience.name}, hosts $newHosts " +
                                "vs $knownHosts); restarting stream"
                        )
                        restartForAssignment(current, newApp)
                    }
                }
                // No active assignment: a self-service stream. Leave it
                // alone; it ends only on user exit or on error.
            }
            else -> {}
        }

        // 4. Heartbeat, last, so it reports the state this tick produced.
        sendHeartbeat(apiClient)
    }

    /** The server assignment changed while streaming: stop, then restart. */
    private fun restartForAssignment(current: State.Streaming, newApp: String?) {
        streamHooks?.stopStream()
        try {
            client?.deleteStream(current.bodyId)
        } catch (e: IOException) {
            Log.w(TAG, "deleteStream on assignment change failed: ${e.message}")
        }
        // Back to the idle interval; onStreamEstablished restores 5 s.
        scheduleTick(IDLE_TICK_SECONDS)
        val experience = cachedCatalog.firstOrNull { it.name == newApp }
            ?: Experience(newApp ?: "", newApp ?: "", null, false)
        startExperience(experience, selfService = false)
    }

    // ------------------------------------------------------------------
    // Experience start: discovery -> pairing -> streaming
    // ------------------------------------------------------------------

    private fun startExperience(experience: Experience, selfService: Boolean) {
        val apiClient = client ?: return
        val config = cachedConfig
        val headId = enrollment?.headId ?: return
        setState(State.Discovering(experience))
        try {
            val bodies = apiClient.getEligibleBodies(
                district = config?.district,
                venue = config?.venue,
                headId = headId,
                experience = experience.name
            )
            // Pick the FIRST body with stream_count == 0 (missing counts as 0).
            val body = bodies.firstOrNull { it.streamCount == 0 }
            if (body == null) {
                setState(State.Error("No body available for this experience"))
                return
            }
            val bodyId = body.id ?: ""
            val host = selectHost(body)
            if (host == null) {
                setState(State.Error("Body has no reachable IP"))
                return
            }
            // Remember every address of this body: the tick matches the
            // server's stream block against the full set, since the block
            // may name a different address of the same body than the one
            // the head streams from.
            currentBodyHosts = body.candidateHosts()
            setState(State.Pairing(body.name ?: bodyId, host, experience))
            val hooks = streamHooks
            if (hooks != null) {
                val headConfig = config ?: HeadConfig(
                    null, null, null, null, null, null, null
                )
                hooks.launchStream(host, experience, headConfig, bodyId, selfService)
                // The integration layer calls onStreamEstablished() or
                // onStreamInterrupted() from the NvConnectionListener callbacks.
            } else {
                // HydraApp wires HydraStreamHooks at startup, so this branch
                // only fires when HydraApp is not registered in the manifest.
                Log.w(TAG, "StreamHooks not wired; cannot stream")
                setState(State.Error("Streaming is not available (no stream hooks)"))
            }
        } catch (e: IOException) {
            setState(State.Error("Body discovery failed: ${e.message}"))
        }
    }

    /**
     * Pick a reachable host for the body. Probe each candidate on TCP 47990
     * with a 1 s timeout: LAN ip first, then the WireGuard ip, then the ip
     * again when it was not RFC1918. When nothing answers, use the first
     * candidate anyway.
     */
    private fun selectHost(body: EligibleBody): String? {
        val candidates = body.candidateHosts()
        if (candidates.isEmpty()) return null
        for (candidate in candidates) {
            if (probeTcp(candidate, SUNSHINE_API_PORT, PROBE_TIMEOUT_MS) != null) {
                return candidate
            }
        }
        return candidates.first()
    }

    /** TCP connect probe. Returns the RTT in ms, or null when unreachable. */
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

    // ------------------------------------------------------------------
    // Heartbeat
    // ------------------------------------------------------------------

    private fun sendHeartbeat(apiClient: HydraClusterClient) {
        val current = state
        val status = statusFor(current)
        val bodyId = (current as? State.Streaming)?.bodyId
        try {
            apiClient.putHeartbeat(status, bodyId, collectDiagnostics())
        } catch (e: IOException) {
            Log.w(TAG, "heartbeat failed: ${e.message}")
        }
    }

    /**
     * Build the diagnostics block the heartbeat sends. Also shown in the
     * operator diagnostics view, so both always agree. Blocking: probes the
     * assignment host on TCP 47990 with a 5 s cap. Never call this on the
     * main thread.
     */
    fun collectDiagnostics(): HeadDiagnostics {
        // Latency and routing always describe the resolved assignment host
        // from head config, like the iPad app. "?" and "unknown" when the
        // config carries no assignment.
        val host = cachedConfig?.streamHost
        val routing = when {
            host == null -> "unknown"
            host.startsWith("10.10.") -> "wireguard"
            else -> "lan"
        }
        val latency = if (host != null) {
            probeTcp(host, SUNSHINE_API_PORT, LATENCY_CAP_MS)?.toString() ?: "?"
        } else {
            "?"
        }
        val clientId = try {
            moonlightClientIdProvider?.invoke()
        } catch (e: Exception) {
            null
        }
        val wireguardStatus = try {
            wireguardStatusProvider?.invoke() ?: "disabled"
        } catch (e: Exception) {
            "error: ${e.message ?: "status failed"}".take(70)
        }
        return HeadDiagnostics(
            version = "v" + BuildConfig.VERSION_NAME,
            wireguard = wireguardStatus,
            routing = routing,
            latencyMs = latency,
            wifiSsid = currentSsid(),
            localIp = localIpAddress(),
            moonlightClientId = clientId
        )
    }

    private fun statusFor(state: State): String = when (state) {
        is State.Unconfigured, is State.Idle -> "idle"
        is State.SelfService -> "self-service"
        is State.Discovering, is State.Pairing -> "starting"
        is State.Streaming -> "streaming"
        is State.Error -> "error"
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(): String {
        return try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return "unknown"
            val ssid = wifi.connectionInfo?.ssid ?: return "unknown"
            val cleaned = ssid.trim('"')
            if (cleaned.isEmpty() || cleaned == "<unknown ssid>") "unknown" else cleaned
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun localIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "unknown"
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (address in iface.inetAddresses) {
                    val host = address.hostAddress ?: continue
                    // IPv4 only; the fleet diagnostics expect dotted quads.
                    if (!address.isLoopbackAddress && !host.contains(':')) {
                        return host
                    }
                }
            }
            "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    // ------------------------------------------------------------------
    // Command poll, every 3 s
    // ------------------------------------------------------------------

    private fun scheduleCommandPoll() {
        commandFuture?.cancel(false)
        commandFuture = executor.scheduleWithFixedDelay(
            { safeCommandPoll() }, COMMAND_POLL_SECONDS, COMMAND_POLL_SECONDS, TimeUnit.SECONDS
        )
    }

    private fun safeCommandPoll() {
        try {
            val apiClient = client ?: return
            val command = apiClient.getCommands()
            if (command.screenshot) {
                val bytes = screenshotProvider?.invoke()
                if (bytes != null) {
                    apiClient.postScreenshot(bytes)
                } else {
                    Log.w(TAG, "screenshot requested but no provider available")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "command poll failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Sunshine PIN post
    // ------------------------------------------------------------------

    /**
     * Post the pairing PIN to the Sunshine API on the body.
     *
     * POST https://host:47990/api/pin with Basic auth and body {"pin": ...}.
     * Sunshine serves a self-signed certificate, so this one connection
     * trusts all certs. Never apply this trust manager to hydracluster calls.
     *
     * The caller must delay PIN_POST_DELAY_MS (0.3 s) after returning the PIN
     * from the pairing callback, so the PIN lands while the /pair
     * getservercert request is still pending.
     */
    @Throws(IOException::class)
    fun postPinToSunshine(host: String, username: String, password: String, pin: String) {
        val url = URL("https://$host:$SUNSHINE_API_PORT/api/pin")
        val conn = url.openConnection() as HttpURLConnection
        try {
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = insecureSslContext().socketFactory
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            conn.requestMethod = "POST"
            conn.connectTimeout = PIN_POST_TIMEOUT_MS
            conn.readTimeout = PIN_POST_TIMEOUT_MS
            val credentials = android.util.Base64.encodeToString(
                "$username:$password".toByteArray(StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            conn.setRequestProperty("Authorization", "Basic $credentials")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().put("pin", pin).toString()
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("Sunshine PIN post failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun insecureSslContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<X509TrustManager>(trustAll), SecureRandom())
        return context
    }

    // ------------------------------------------------------------------
    // State plumbing
    // ------------------------------------------------------------------

    private fun setState(newState: State) {
        state = newState
        listener?.onStateChanged(newState)
    }
}
