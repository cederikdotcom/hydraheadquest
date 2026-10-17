package com.limelight.hydra

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.limelight.BuildConfig
import com.limelight.hydra.model.EligibleBody
import com.limelight.hydra.model.EnrollmentConfig
import com.limelight.hydra.model.Experience
import com.limelight.hydra.model.HeadConfig
import com.limelight.hydra.model.HeadDiagnostics
import com.limelight.hydra.model.XrSessionState
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
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

        /**
         * Read a GET /api/pin response into pending pairings.
         *
         * Wire verified against a live Sunshine 2026.914.233613 body
         * mid-handshake, envelope and all three field names:
         *
         *     {"pairings":[{"address":"10.10.100.11",
         *                   "id":"e6223860b14a02089139f0b13b2f709e",
         *                   "name":"roth"}]}
         *
         * `id` is 32 lowercase hex. `address` is the client's source IP as
         * the server sees it, which is what makes address matching viable.
         * `name` is the devicename the client sent, so it is the same
         * useless `roth` for every head still on the legacy identity.
         * Entries appear about two seconds after getservercert, and the list
         * is `{"pairings":[]}` when idle.
         *
         * Fields are still read defensively, because one verified build is
         * not every build: an entry with no usable id is skipped rather than
         * guessed at, and a response shaped unlike this reads as "this body
         * does not offer pending pairings" instead of failing the pair.
         */
        fun parsePendingPairings(text: String): List<PendingPairing> {
            return try {
                val entries: JSONArray = JSONObject(text).optJSONArray("pairings")
                    ?: return emptyList()
                (0 until entries.length()).mapNotNull { i ->
                    val entry = entries.optJSONObject(i) ?: return@mapNotNull null
                    val id = entry.optString("id", "")
                    if (id.isEmpty()) return@mapNotNull null
                    PendingPairing(
                        id = id,
                        name = entry.optString("name", "").ifEmpty { null },
                        address = entry.optString("address", "").ifEmpty { null }
                    )
                }
            } catch (e: org.json.JSONException) {
                Log.w(TAG, "unparsable pending pairings: ${e.message}")
                emptyList()
            }
        }

        /**
         * Pick this head's pending pairing out of the reported list.
         *
         * Filtering is by source address when the body reports one, never by
         * name. A head on the legacy identity sends the same hardcoded
         * devicename `roth` as every other, and even with HydraIdentity the
         * address is the only field the SERVER observes rather than accepts
         * from the client. More than one candidate throws rather than
         * choosing: posting our PIN against another client's pairing id
         * would pair the wrong device, and the symptom is a phase 4
         * rejection indistinguishable from an unrelated bug.
         */
        @Throws(IOException::class)
        fun selectPendingPairing(
            pending: List<PendingPairing>,
            localAddress: String?
        ): PendingPairing? {
            val matched = if (localAddress != null && pending.any { it.address != null }) {
                pending.filter { it.address == localAddress }
            } else {
                pending
            }
            // Zero matches is not ambiguity, it is the absence of a usable
            // discriminator, and it is the NORMAL state behind a masquerading
            // neck: the body records the post-NAT address while we compare the
            // private one we hold, so they can never be equal. Refusing there
            // leaves a head behind a neck unable to pair at all, even alone.
            // Fall back to the whole list, where exactly one entry on a body we
            // are mid-handshake with is ours.
            val candidates = if (matched.isEmpty()) pending else matched
            return when (candidates.size) {
                0 -> null
                1 -> candidates[0]
                else -> throw IOException(
                    "refusing to guess among ${candidates.size} pending pairings"
                )
            }
        }

        /**
         * The POST /api/pin body.
         *
         * `pairing_id` and `name` are added only when the body minted an id,
         * which only the newer Sunshine generation does. An older body gets
         * the bare form it accepts, rather than relying on it ignoring
         * fields it does not know.
         */
        fun buildPinPayload(pin: String, pairingId: String?, deviceName: String?): String {
            val payload = JSONObject().put("pin", pin)
            if (pairingId != null) {
                payload.put("pairing_id", pairingId)
                if (deviceName != null) payload.put("name", deviceName)
            }
            return payload.toString()
        }

        /**
         * Head-side cap on the XR arming wait. Sized above the cluster's
         * 150 s arm timeout plus one poll: the honest worst case for the
         * ALVR chain cold start is about 110 s (design section 2.2).
         */
        const val XR_ARMING_TIMEOUT_SECONDS = 180L
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

        /**
         * The cluster is arming an XR body for this head (issue #558):
         * ALVR chain start plus client trust take 45 to 90 s, worst
         * about 110 s. The 5 s tick polls the XR session until it is
         * armed, failed, or timed out.
         */
        data class ArmingXr(
            val bodyName: String,
            val host: String,
            val experience: Experience,
            val bodyId: String
        ) : State()

        /**
         * The ALVR client is launched and the body runs the experience.
         * The body is the source of truth: the 5 s tick polls the XR
         * session, and a gone, ending, or failed session IS the end
         * signal. There is no client-side process detection.
         */
        data class StreamingXr(
            val host: String,
            val experience: Experience,
            val bodyId: String,
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

    /**
     * Integration surface toward the separate ALVR client app (issue
     * #558). Implemented by [AlvrLauncher]; HydraApp wires the instance
     * at startup. Kept apart from [StreamHooks] so the flat streaming
     * integration stays untouched.
     */
    interface XrHooks {
        /**
         * The VR driver this launcher speaks, named as the cluster names
         * it in xr_drivers: "alvr", "wivrn".
         */
        val driverName: String

        /** True when this driver's client package is installed. */
        fun isXrClientInstalled(): Boolean

        /**
         * Launch (or foreground) the ALVR client with the VR category
         * from the current foreground activity. Returns false when
         * there is no foreground activity (vrshell rejects VR-category
         * launches from a background context) or the launch failed.
         */
        fun launchXrClient(): Boolean
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

    @Volatile
    var xrHooks: XrHooks? = null

    /**
     * Supplies this head's own mesh address (the WireGuard Interface
     * Address, for example "10.10.200.7"), sent as client_ip in the XR
     * session request so the body can trust the ALVR client at that
     * address. Null when no tunnel config is stored. HydraApp wires
     * this to HydraWireGuard.tunnelAddress().
     */
    @Volatile
    var wireguardAddressProvider: (() -> String?)? = null

    /**
     * Screenshot provider for the remote screenshot command. Returns JPEG
     * bytes (quality 70) or null when capture is not possible. HydraApp
     * wires this to [HydraScreenshot] (PixelCopy of the current activity),
     * which captures a flat stream (SurfaceView included on API 26+).
     *
     * An XR stream cannot be captured at all: it renders in the separate
     * ALVR or WiVRn client's XR swapchain, another process this app cannot
     * read (issue #790). [captureScreenshotOrExplain] detects that case and
     * the no-foreground case and returns a labelled card, so the operator
     * always gets an honest frame instead of a black one or nothing.
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
     * Supplies the VR drivers this headset can speak, in preference order,
     * for the heartbeat. A body advertises what it can serve in
     * xr_drivers; this is the head's half of the same question, and it is
     * what lets the cluster match the two instead of assuming alvr.
     * Empty before wiring, which reads as "no VR driver installed".
     */
    @Volatile
    var xrDriversProvider: (() -> List<String>)? = null


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

    /**
     * Whether the current XR flow was started from the grid. ArmingXr
     * carries no selfService flag, so this remembers it between the
     * session request and the StreamingXr state. Executor-confined.
     */
    private var xrSelfService: Boolean = false

    /**
     * When the current XR arming wait started (elapsedRealtime ms), for
     * the head-side 180 s cap. Executor-confined.
     */
    private var xrArmingStartedAt: Long = 0L

    /**
     * When the user started the current experience (elapsedRealtime ms),
     * used to measure the stream start time reported in diagnostics
     * (issue #789). Set in [startExperience], consumed once by
     * [recordStreamShown], then zeroed so an unrelated later resume cannot
     * record a stale value. Written from the executor and the main thread
     * (the lifecycle callback), so it is volatile.
     */
    @Volatile
    private var streamLaunchStartedAt: Long = 0L

    /**
     * The last measured stream start time in ms, or null before the first
     * stream of this process. Reported as last_stream_start_ms in the
     * heartbeat and the operator diagnostics view.
     */
    @Volatile
    private var lastStreamStartMs: Long? = null

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
    /**
     * Cancel a start in progress (Discovering, Pairing, or ArmingXr).
     * Added after hardware testing: a start that hangs left the kiosk
     * with a spinner and no way out.
     */
    fun onUserCancelStart() {
        executor.execute {
            when (val current = state) {
                is State.ArmingXr -> endXrArming(current, "Cancelled")
                is State.Discovering, is State.Pairing -> {
                    setState(State.SelfService(cachedCatalog))
                    scheduleTick(IDLE_TICK_SECONDS)
                }
                else -> {}
            }
        }
    }

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

    /**
     * A stream activity (Game) became visible in this process, so the flat
     * stream is showing. Records the stream start time (issue #789). Called
     * from the main thread by HydraApp's lifecycle callback; hops to the
     * executor so the timing fields stay confined there.
     */
    fun onGameShown() {
        executor.execute { recordStreamShown("flat") }
    }

    /**
     * Consume the launch-start mark set in [startExperience] and record the
     * stream start time, once. [lane] only labels the log line. A 120 s
     * ceiling drops a stale mark, for example an unrelated resume long after
     * a start that never produced a stream. Executor-confined.
     */
    private fun recordStreamShown(lane: String) {
        val start = streamLaunchStartedAt
        if (start <= 0L) {
            return
        }
        streamLaunchStartedAt = 0L
        val delta = SystemClock.elapsedRealtime() - start
        if (delta in 1L..120000L) {
            lastStreamStartMs = delta
            Log.i(TAG, "stream start ($lane): $delta ms")
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
        } catch (t: Throwable) {
            // Throwable, not Exception: a linkage error (VerifyError,
            // NoClassDefFoundError) must be visible too. The scheduled
            // executor otherwise swallows anything a task throws.
            Log.e(TAG, "tick failed", t)
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
                        if (config.stream?.streamMode == Experience.STREAM_MODE_XR) {
                            // An XR assignment (issue #558): the stream
                            // block names the admin-pinned body through
                            // its URLs, so prefer the eligible body
                            // matching those hosts.
                            startXrExperience(
                                experience,
                                selfService = false,
                                preferredHosts = assignmentHosts(config)
                            )
                        } else {
                            startExperience(experience, selfService = false)
                        }
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
            is State.ArmingXr -> tickArmingXr(current, apiClient)
            is State.StreamingXr -> tickStreamingXr(current, apiClient)
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
        // Mark the start of both lanes here so the stream start time covers
        // discovery, pairing or arming, and the launch (issue #789).
        streamLaunchStartedAt = SystemClock.elapsedRealtime()
        if (experience.isXr) {
            // The immersive ALVR path (issue #558). The flat flow below
            // stays byte-identical for every other experience.
            startXrExperience(experience, selfService)
            return
        }
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
            Log.i(TAG, "xr body chosen: ${body.name} ($bodyId), probing hosts")
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

    // ------------------------------------------------------------------
    // XR session flow (issue #558): request -> arming -> streamingXr
    // ------------------------------------------------------------------

    /**
     * Start an XR experience: pre-flight the ALVR client, discover an
     * XR-capable body, POST the XR session, then wait in [State.ArmingXr]
     * while the body arms the ALVR chain (design section 4.2).
     *
     * [preferredHosts] is non-empty only on the assignment path: the
     * stream block's hosts name the admin-pinned body, and the eligible
     * body matching them is preferred so the assignment arms the pinned
     * body, not merely the first free one.
     */
    private fun startXrExperience(
        experience: Experience,
        selfService: Boolean,
        preferredHosts: List<String> = emptyList()
    ) {
        val apiClient = client ?: return
        val config = cachedConfig
        val headId = enrollment?.headId ?: return

        // Pre-flight: the VR driver client is a separate APK. Without it
        // an armed body could never be joined, so fail before any session
        // request is made. Which client this is depends on the driver:
        // alvr for a Windows body, wivrn for a Linux one.
        val hooks = xrHooks
        if (hooks == null || !hooks.isXrClientInstalled()) {
            setState(State.Error("No VR driver client installed on this headset"))
            return
        }
        val clientHostname = config?.xrClientHostname
        if (config == null || clientHostname.isNullOrEmpty()) {
            // Admin misconfiguration surfaced clearly instead of as a
            // trust failure and arm timeout a minute later. The null
            // config check also gives the smart cast below.
            setState(State.Error("No ALVR client hostname is configured for this head"))
            return
        }

        setState(State.Discovering(experience))

        try {
            startXrExperienceInner(experience, selfService, preferredHosts, apiClient, config, headId, clientHostname)
        } catch (t: Throwable) {
            // The scheduled executor swallows uncaught throwables without
            // a trace (three silent deaths on hardware, 2026-08-27), so
            // everything is caught here, logged, and surfaced.
            Log.e(TAG, "xr start crashed", t)
            setState(State.Error("XR start failed: ${t.javaClass.simpleName}: ${t.message}"))
            scheduleTick(IDLE_TICK_SECONDS)
        }
    }

    private fun startXrExperienceInner(
        experience: Experience,
        selfService: Boolean,
        preferredHosts: List<String>,
        apiClient: HydraClusterClient,
        config: HeadConfig,
        headId: String,
        clientHostname: String
    ) {
        Log.i(TAG, "xr start: ${experience.name} selfService=$selfService")
        // This head may already own a session server-side, typically
        // after an app restart mid-session. Resume it instead of failing
        // (an engaged body is filtered out of the XR eligible query, and
        // a new POST would 409 head_busy). An old cluster 404s here,
        // which the client surfaces as null: nothing changes for it.
        val existing: XrSessionState? = try {
            apiClient.getXrSession()
        } catch (e: IOException) {
            Log.w(TAG, "xr-session pre-check failed: ${e.message}")
            null
        }
        Log.i(TAG, "xr pre-check: existing=${existing?.state ?: "none"}")
        if (existing != null) {
            resumeOrClearXrSession(existing, experience, selfService)
            return
        }

        try {
            val bodies = apiClient.getEligibleBodies(
                district = config.district,
                venue = config.venue,
                headId = headId,
                experience = experience.name,
                streamMode = Experience.STREAM_MODE_XR
            )
            // Client-side re-filter: an old cluster ignores the
            // stream_mode parameter and returns flat bodies without
            // xr_drivers, so requiring "alvr" here keeps them out.
            // streamCount == 0 as on the flat path.
            val eligible = bodies.filter {
                it.streamCount == 0 && it.xrDrivers.contains("alvr")
            }
            Log.i(TAG, "xr eligible: ${bodies.size} bodies, ${eligible.size} xr-capable")
            // Assignment path: prefer the pinned body named by the
            // stream block hosts. Self-service passes no hosts and
            // takes the first eligible body, as on the flat path.
            var body: EligibleBody? = null
            if (preferredHosts.isNotEmpty()) {
                body = eligible.firstOrNull { candidate ->
                    candidate.candidateHosts().any { it in preferredHosts }
                }
            }
            if (body == null) {
                body = eligible.firstOrNull()
            }
            if (body == null) {
                setState(State.Error("No XR bodies available"))
                return
            }
            val bodyId = body.id ?: ""
            Log.i(TAG, "xr body chosen: ${body.name} ($bodyId), probing hosts")
            val host = selectHost(body)
            if (host == null) {
                setState(State.Error("Body has no reachable IP"))
                return
            }
            // Same capture as the flat path: the tick compares the
            // server's stream block against the full candidate set (the
            // v0.4.0 tick-kill guard depends on it).
            currentBodyHosts = body.candidateHosts()
            xrSelfService = selfService
            val meshIp = try {
                wireguardAddressProvider?.invoke()
            } catch (e: Exception) {
                null
            }
            // The mesh address is what the body trusts; the local IP is
            // the same-LAN fallback when no tunnel is configured.
            val clientIp = meshIp ?: localIpAddress().takeIf { it != "unknown" }
            Log.i(TAG, "xr posting session: body=$bodyId host=$host clientIp=$clientIp")
            apiClient.postXrSession(bodyId, experience.name, clientHostname, clientIp)
            Log.i(TAG, "xr session created, arming")
            xrArmingStartedAt = SystemClock.elapsedRealtime()
            setState(State.ArmingXr(body.name ?: bodyId, host, experience, bodyId))
            // Streaming cadence: the arming poll runs on the 5 s tick.
            scheduleTick(STREAMING_TICK_SECONDS)
        } catch (e: HydraClusterClient.HttpStatusException) {
            val message = when {
                e.code == 404 -> "Cluster does not support XR heads"
                e.message?.contains("body_busy") == true ->
                    "The body is busy with another session"
                e.message?.contains("not_xr_capable") == true ->
                    "The body is not XR capable"
                // Race fallback only; the pre-check above normally
                // resumes or ends an existing session before the POST.
                e.message?.contains("head_busy") == true ->
                    "This head already has a headset session. Try again in a moment."
                else -> "Could not start XR session: ${e.message}"
            }
            setState(State.Error(message))
        } catch (e: IOException) {
            setState(State.Error("Could not start XR session: ${e.message}"))
        }
    }

    /**
     * This head already owns a server-side XR session (found by the
     * pre-check in [startXrExperience], typically after an app restart
     * mid-session). A live session for the SAME experience is adopted:
     * re-enter [State.ArmingXr] and let the poll take it from there (a
     * restart must never bounce a running session, least of all an
     * assignment). Anything else is ended so a retry starts clean.
     */
    private fun resumeOrClearXrSession(
        existing: XrSessionState,
        experience: Experience,
        selfService: Boolean
    ) {
        if (existing.isLive && existing.experience == experience.name) {
            val bodyId = existing.bodyId ?: ""
            currentBodyHosts = listOfNotNull(existing.bodyHost)
            xrSelfService = selfService
            xrArmingStartedAt = SystemClock.elapsedRealtime()
            setState(
                State.ArmingXr(bodyId, existing.bodyHost ?: "", experience, bodyId)
            )
            scheduleTick(STREAMING_TICK_SECONDS)
            return
        }
        try {
            client?.deleteXrSession()
        } catch (e: IOException) {
            Log.w(TAG, "deleteXrSession on stale session failed: ${e.message}")
        }
        setState(State.Error("A previous headset session is ending. Try again in a moment."))
        scheduleTick(IDLE_TICK_SECONDS)
    }

    /** Seconds spent in the current arming wait. */
    private fun xrArmingElapsedSeconds(): Long =
        (SystemClock.elapsedRealtime() - xrArmingStartedAt) / 1000

    /**
     * Arming poll, on the 5 s tick. Armed => launch the ALVR client.
     * Failed, gone, ending, or over the 180 s cap => end with an error.
     * Anything else (requested, arming) => keep waiting.
     */
    private fun tickArmingXr(current: State.ArmingXr, apiClient: HydraClusterClient) {
        val session: XrSessionState? = try {
            apiClient.getXrSession()
        } catch (e: IOException) {
            Log.w(TAG, "xr-session poll failed: ${e.message}")
            // Transient failure: keep waiting unless the cap is blown.
            if (xrArmingElapsedSeconds() <= XR_ARMING_TIMEOUT_SECONDS) return
            null
        }
        when {
            // "active" covers a body that resumed a session after a
            // hydrabody restart with the client already connected.
            session?.state == "armed" || session?.state == "active" -> {
                val hooks = xrHooks
                if (hooks == null || !hooks.launchXrClient()) {
                    // No foreground activity (vrshell would reject the
                    // VR-category launch) or the launch threw.
                    endXrArming(current, "Could not launch the ALVR client")
                    return
                }
                setState(
                    State.StreamingXr(
                        current.host, current.experience, current.bodyId, xrSelfService
                    )
                )
                // The body armed and the client launched: the XR stream is
                // starting from the head's point of view (issue #789).
                recordStreamShown("xr")
                // Already on the 5 s cadence; the StreamingXr poll keeps it.
            }
            session == null ||
                session.state == "failed" ||
                session.state == "ending" ||
                xrArmingElapsedSeconds() > XR_ARMING_TIMEOUT_SECONDS -> {
                endXrArming(current, session?.reason)
            }
            else -> {
                // requested or arming: keep waiting on the 5 s tick.
            }
        }
    }

    /** Arming failed or timed out: delete, mark, error, idle cadence. */
    private fun endXrArming(current: State.ArmingXr, reason: String?) {
        try {
            client?.deleteXrSession()
        } catch (e: IOException) {
            Log.w(TAG, "deleteXrSession on arming end failed: ${e.message}")
        }
        markEndedXr(current.bodyId, current.experience.name, xrSelfService)
        val message = reason?.takeIf { it.isNotEmpty() }
            ?.let { "Headset session failed: $it" }
            ?: "Headset session could not start"
        setState(State.Error(message))
        scheduleTick(IDLE_TICK_SECONDS)
    }

    /**
     * XR session poll while streaming. The body is the source of truth:
     * a gone (404), ending, or failed session IS the end signal. A
     * transport failure is transient and never ends the session.
     */
    private fun tickStreamingXr(current: State.StreamingXr, apiClient: HydraClusterClient) {
        val session: XrSessionState? = try {
            apiClient.getXrSession()
        } catch (e: IOException) {
            Log.w(TAG, "xr-session poll failed: ${e.message}")
            return
        }
        if (session == null || session.state == "ending" || session.state == "failed") {
            // Ended: doff grace expired, the user pressed End, or the
            // body failed. Same cleanup as onStreamEnded: send the
            // stream DELETE and mark the ended session, so any stale
            // stream block a cluster may still serve (#137) is guarded.
            markEndedXr(current.bodyId, current.experience.name, current.selfService)
            try {
                client?.deleteStream(current.bodyId)
            } catch (e: IOException) {
                Log.w(TAG, "deleteStream on xr end failed: ${e.message}")
            }
            setState(State.SelfService(cachedCatalog))
            scheduleTick(IDLE_TICK_SECONDS)
        }
        // Any live state (armed, active, draining): leave it alone. A
        // draining body is inside its doff grace and may resume.
    }

    /**
     * XR twin of [markEnded]: remember a self-service XR session on its
     * way out, fed from the captured candidate host set, so the tick
     * never relaunches it from the head's own stale stream block.
     * Assignment sessions are NOT marked, same as on flat.
     */
    private fun markEndedXr(bodyId: String, app: String, selfService: Boolean) {
        if (!selfService) return
        endedSession = EndedSession(
            bodyId = bodyId,
            app = app,
            hosts = currentBodyHosts
        )
    }

    /**
     * The user tapped End session on the catalog while in an XR session.
     * DELETE marks the session ending server-side; the body tears down
     * on its next poll. The immediate tick runs the common end path
     * without waiting up to 5 s. Never kills anything locally: the body
     * may be inside its doff grace.
     */
    fun onUserEndXrSession() {
        executor.execute {
            if (state !is State.StreamingXr) return@execute
            try {
                client?.deleteXrSession()
            } catch (e: IOException) {
                Log.w(TAG, "deleteXrSession on user end failed: ${e.message}")
            }
            safeTick()
        }
    }

    /** The user tapped Return to experience: bring the ALVR client forward. */
    fun onReturnToXr() {
        executor.execute {
            if (state !is State.StreamingXr) return@execute
            val hooks = xrHooks
            if (hooks == null || !hooks.launchXrClient()) {
                Log.w(TAG, "could not return to the ALVR client")
            }
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
        // XR states report their body too: the cluster stores it as the
        // head's live body for operator attribution. XR slot exclusivity
        // itself comes from the cluster's XRSession record, and the XR
        // end path still runs the flat-style cleanup so any stale stream
        // block a cluster may serve (#137) stays guarded.
        val bodyId = when (current) {
            is State.Streaming -> current.bodyId
            is State.ArmingXr -> current.bodyId
            is State.StreamingXr -> current.bodyId
            else -> null
        }
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
        // The driver actually in use while an XR session is arming or
        // running, so the operator table and the heartbeat agree; absent
        // otherwise. Reporting a hardcoded "alvr" here was honest only
        // while alvr was the sole driver.
        val current = state
        val xrClient = if (current is State.ArmingXr || current is State.StreamingXr) {
            xrHooks?.driverName ?: XrDrivers.ALVR
        } else {
            null
        }
        val xrDrivers = try {
            xrDriversProvider?.invoke() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return HeadDiagnostics(
            version = "v" + BuildConfig.VERSION_NAME,
            wireguard = wireguardStatus,
            routing = routing,
            latencyMs = latency,
            wifiSsid = currentSsid(),
            localIp = localIpAddress(),
            moonlightClientId = clientId,
            xrClient = xrClient,
            xrDrivers = xrDrivers,
            lastStreamStartMs = lastStreamStartMs
        )
    }

    private fun statusFor(state: State): String = when (state) {
        is State.Unconfigured, is State.Idle -> "idle"
        is State.SelfService -> "self-service"
        is State.Discovering, is State.Pairing -> "starting"
        is State.Streaming -> "streaming"
        is State.ArmingXr -> "pairing"
        is State.StreamingXr -> "streaming-xr"
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
                val bytes = captureScreenshotOrExplain()
                if (bytes != null) {
                    apiClient.postScreenshot(bytes)
                } else {
                    Log.w(TAG, "screenshot requested but none could be produced")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "command poll failed: ${e.message}")
        }
    }

    /**
     * Produce the screenshot bytes for the remote command (issue #790). An
     * XR stream renders in the separate ALVR or WiVRn client's XR swapchain,
     * another process this app cannot read, so there is nothing in our
     * window to capture. Rather than upload a black frame or send nothing,
     * return a labelled card that tells the operator why. A failed real
     * capture (no foreground activity) gets the same honest treatment.
     */
    private fun captureScreenshotOrExplain(): ByteArray? {
        if (state is State.StreamingXr) {
            val driver = xrHooks?.driverName ?: "XR"
            return HydraScreenshot.placeholder(
                "XR stream in progress",
                "The headset view renders in the $driver client, which the " +
                    "kiosk cannot capture. Watch the stream on the body instead."
            )
        }
        val real = try {
            screenshotProvider?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "screenshot provider threw: ${e.message}")
            null
        }
        if (real != null) {
            return real
        }
        return HydraScreenshot.placeholder(
            "No screen to capture",
            "The kiosk is not in the foreground right now."
        )
    }

    // ------------------------------------------------------------------
    // Sunshine PIN post
    // ------------------------------------------------------------------

    /** One pending pairing as Sunshine reports it on GET /api/pin. */
    data class PendingPairing(val id: String, val name: String?, val address: String?)

    /**
     * Open a connection to the Sunshine API with Basic auth.
     *
     * Sunshine serves a self-signed certificate, so these connections trust
     * all certs. Never apply this trust manager to hydracluster calls.
     */
    private fun openSunshineApi(host: String, path: String, username: String, password: String):
        HttpURLConnection {
        val conn = URL("https://$host:$SUNSHINE_API_PORT$path").openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = insecureSslContext().socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        conn.connectTimeout = PIN_POST_TIMEOUT_MS
        conn.readTimeout = PIN_POST_TIMEOUT_MS
        val credentials = android.util.Base64.encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        conn.setRequestProperty("Authorization", "Basic $credentials")
        return conn
    }

    /**
     * The local source address this head uses to reach [host].
     *
     * Connecting a datagram socket sends nothing on the wire; it only asks
     * the kernel to pick a route and bind a source address. Returns null if
     * the route cannot be resolved, in which case the caller must not try to
     * identify its own pending pairing by address.
     */
    fun localAddressFor(host: String): String? = try {
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName(host), SUNSHINE_API_PORT)
            socket.localAddress?.hostAddress
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not resolve a local address for $host: ${e.message}")
        null
    }

    /**
     * Pending pairings from GET https://host:47990/api/pin.
     *
     * The envelope `{"pairings":[...]}` is wire verified against a
     * 2026.914 body. The per-entry names are derived from Sunshine's
     * `pending_pairing_t`, NOT wire captured, so every field is read
     * defensively and an entry without a usable id is skipped rather than
     * guessed at. An empty list and a server with no such route both yield
     * an empty result, which the caller treats as "legacy generation".
     */
    @Throws(IOException::class)
    fun fetchPendingPairings(host: String, username: String, password: String): List<PendingPairing> {
        val conn = openSunshineApi(host, "/api/pin", username, password)
        try {
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) {
                // Any refusal reads as "this body has no pending-pairing
                // concept" and falls back to the legacy post, rather than
                // failing the pair. A transient 500 on this route must not
                // stop us pairing with a body that never needed an id. The
                // code is logged so a real server fault stays visible
                // instead of being silently absorbed.
                Log.w(TAG, "pending pairings unavailable on $host: HTTP $code, using the legacy PIN post")
                return emptyList()
            }
            val text = conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            return parsePendingPairings(text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * This head's pending pairing, or null if the body does not mint one.
     *
     * The whole entry is returned rather than just the id so the caller can
     * echo back the name the server itself recorded, instead of guessing it.
     * 2026.914 does not actually validate `name` against the pending entry,
     * so echoing it is not load bearing on that build, but guessing a value
     * the server never recorded would be a worse default for builds that do.
     */
    @Throws(IOException::class)
    fun resolvePendingPairing(host: String, username: String, password: String): PendingPairing? {
        val pending = fetchPendingPairings(host, username, password)
        if (pending.isEmpty()) return null
        return selectPendingPairing(pending, localAddressFor(host))
    }

    /**
     * Post the pairing PIN to the Sunshine API on the body.
     *
     * POST https://host:47990/api/pin with Basic auth. Two generations of
     * Sunshine want different bodies, and the fleet runs both:
     *
     * - 2025.924 and earlier accept `{"pin": ...}`.
     * - 2026.914 requires a server minted 32 hex character `pairing_id`
     *   plus a `name`, and rejects the bare form with HTTP 400
     *   "pairing_id must contain exactly 32 hexadecimal characters". Every
     *   cloud body runs this generation, so without the id a Quest head
     *   cannot pair with the hyperscaler at all.
     *
     * So [pairingId] is included when the caller could resolve one, which
     * only a body of the newer generation provides. Sending the richer body
     * to an older body is avoided rather than relied upon, because its
     * handling of unknown fields is not verified here.
     *
     * The caller must delay PIN_POST_DELAY_MS (0.3 s) after returning the PIN
     * from the pairing callback, so the PIN lands while the /pair
     * getservercert request is still pending.
     */
    @Throws(IOException::class)
    fun postPinToSunshine(
        host: String,
        username: String,
        password: String,
        pin: String,
        pairingId: String? = null,
        deviceName: String? = null
    ) {
        val conn = openSunshineApi(host, "/api/pin", username, password)
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = buildPinPayload(pin, pairingId, deviceName)
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                // Newer Sunshine explains the refusal in the error body, and
                // that sentence is the difference between a one-line fix and a
                // day of guessing. Carry it into the exception message.
                throw IOException("Sunshine PIN post failed: HTTP $code${sunshineReason(conn)}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The `status_message` Sunshine returns with a refusal, formatted for
     * appending to an error. Empty when the body carries no such field.
     */
    private fun sunshineReason(conn: HttpURLConnection): String = try {
        val text = conn.errorStream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        if (text.isNullOrBlank()) {
            ""
        } else {
            val message = JSONObject(text).optString("status_message", "")
            if (message.isEmpty()) " body=${text.take(200)}" else " $message"
        }
    } catch (e: Exception) {
        ""
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
