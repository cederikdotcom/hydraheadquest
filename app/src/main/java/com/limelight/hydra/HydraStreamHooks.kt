package com.limelight.hydra

import android.content.Context
import android.content.Intent
import android.util.Log
import com.limelight.Game
import com.limelight.GameXR
import com.limelight.binding.PlatformBinding
import com.limelight.hydra.model.Experience
import com.limelight.hydra.model.HeadConfig
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.preferences.PreferenceConfiguration
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * StreamHooks against the real Moonlight core, per contract section 7 and 8.
 *
 * The sequence in [launchStream], mirroring the iPad HydraPairSession:
 * 1. Pair fresh on http://host:47989. NvHTTP discovers the HTTPS port
 *    (47984) from /serverinfo. Never 47990: that is Sunshine's web UI and
 *    pairing never completes against it.
 * 2. The PIN comes from PairingManager.generatePinString(). It is posted to
 *    Sunshine (POST https://host:47990/api/pin) 0.3 s after the pairing
 *    request goes out, because Sunshine only accepts the PIN while the
 *    /pair getservercert request is pending.
 * 3. Already paired (PairStatus 1 in serverinfo, or an ALREADY_IN_PROGRESS
 *    result): unpair with the same uniqueid the pairing code uses (NvHTTP
 *    hardcodes 0123456789ABCDEF), then pair again, at most once. On a
 *    second already-paired outcome, proceed with an empty certificate.
 *    The server certificate is never cached across sessions.
 * 4. Look up the Sunshine app named by the experience (= stream_app_id),
 *    falling back to the first app in the list.
 * 5. Write resolution, 60 fps, and bitrate into the Moonlight stream
 *    preferences (Game reads them from there, not from the intent), then
 *    launch Game or GameXR with the same extras ServerHelper uses.
 *
 * Stream end detection: Game and GameXR are noHistory activities that stop
 * the connection in onStop. HydraApp reports their destruction through
 * [onGameActivityDestroyed]; unless this class asked for the stop itself,
 * that means the user exited or the session ended, and HydraState returns
 * to the grid.
 */
class HydraStreamHooks(
    context: Context,
    private val state: HydraState
) : HydraState.StreamHooks {

    companion object {
        private const val TAG = "HydraStreamHooks"

        /** Bitrate on a venue LAN host, in kbps. */
        const val BITRATE_LAN_KBPS = 150000

        /**
         * Bitrate when the host is on the WireGuard mesh (10.10.*), in
         * kbps. The hub relays every tunnelled byte, so mesh streams must
         * not ask for the LAN rate. The shipped iPad code uses 20000;
         * follow it until the cluster says otherwise.
         */
        const val BITRATE_WIREGUARD_KBPS = 20000

        /** Stream frame rate. */
        const val STREAM_FPS = 60

        /**
         * Cap on the blocking pair call. The getservercert request has no
         * read timeout upstream (the user normally types the PIN), so a
         * failed PIN post would otherwise hang the state machine forever.
         */
        private const val PAIR_TIMEOUT_MS = 45000L

        // Stream preference keys, matching the package-private constants in
        // com.limelight.preferences.PreferenceConfiguration. Game reads its
        // resolution, fps, and bitrate from these, and the resolution parser
        // accepts any "WxH" string, so 1080x1920 portrait works.
        private const val PREF_RESOLUTION = "list_resolution"
        private const val PREF_FPS = "list_fps"
        private const val PREF_BITRATE_KBPS = "seekbar_bitrate_kbps"
    }

    private val appContext: Context = context.applicationContext

    /** Set when stopStream initiated the teardown itself. */
    private val expectedStop = AtomicBoolean(false)

    /**
     * True when the host lies in 10.10.0.0/16: the WireGuard mesh, routed
     * through the hydraguard hub over a 1420-MTU tunnel. Mesh streams use
     * the mesh bitrate and Moonlight's remote tuning (1024-byte video
     * packets instead of the 1392-byte LAN size, STREAM_CFG_REMOTE
     * instead of AUTO). The measured hub path is 42-146 ms RTT with heavy
     * jitter, which is exactly the profile the remote settings exist for,
     * and 1024-byte payloads stay under the tunnel MTU with margin.
     */
    private fun isMeshHost(host: String): Boolean = host.startsWith("10.10.")

    // ------------------------------------------------------------------
    // StreamHooks
    // ------------------------------------------------------------------

    /**
     * Runs on the HydraState executor thread; blocking network is fine
     * here. Reports back through onStreamEstablished or onStreamFailed.
     */
    override fun launchStream(
        host: String,
        experience: Experience,
        headConfig: HeadConfig,
        bodyId: String,
        selfService: Boolean
    ) {
        // Note: expectedStop is NOT cleared here. During an assignment
        // restart the old Game activity can be destroyed while this launch
        // is already pairing, and the pending flag from stopStream() must
        // still swallow that teardown. onGameActivityDestroyed consumes it.
        try {
            val crypto = PlatformBinding.getCryptoProvider(appContext)
            val tuple = ComputerDetails.AddressTuple(host, NvHTTP.DEFAULT_HTTP_PORT)
            // httpsPort 0 = unknown; NvHTTP fetches it from /serverinfo.
            // uniqueId is hardcoded to 0123456789ABCDEF inside NvHTTP, the
            // same id the unpair request must carry (contract section 7).
            val http = NvHTTP(tuple, 0, null, null, crypto)

            var serverInfo = http.getServerInfo(true)
            var unpairAttempted = false

            // Always pair fresh. A PairStatus of 1 means a previous pairing
            // exists, but its certificate is gone with the old session, so
            // unpair and start over.
            if (http.getPairState(serverInfo) == PairingManager.PairState.PAIRED) {
                tryUnpair(http)
                unpairAttempted = true
                serverInfo = http.getServerInfo(true)
            }

            var pairResult = pairWithPin(http, serverInfo, host, headConfig)
            if (pairResult == PairingManager.PairState.ALREADY_IN_PROGRESS && !unpairAttempted) {
                // No plaincert in the response. Unpair once and retry.
                tryUnpair(http)
                serverInfo = http.getServerInfo(true)
                pairResult = pairWithPin(http, serverInfo, host, headConfig)
            }

            val serverCert: X509Certificate? = when (pairResult) {
                PairingManager.PairState.PAIRED ->
                    http.pairingManager.pairedCert
                PairingManager.PairState.ALREADY_IN_PROGRESS -> {
                    // Second already-paired outcome: proceed with an empty
                    // certificate and let the caller fail visibly if TLS
                    // does not come up (contract section 7 step 5).
                    Log.w(TAG, "pairing still reports in-progress after unpair; proceeding without cert")
                    null
                }
                PairingManager.PairState.PIN_WRONG ->
                    throw IOException("Sunshine rejected the pairing PIN")
                else ->
                    throw IOException("Pairing failed (${pairResult})")
            }

            // App lookup over the freshly pinned HTTPS channel. The Sunshine
            // app to launch is named by the experience (= stream_app_id);
            // fall back to the first app on the body.
            val apps = http.appList
            val app: NvApp = apps.firstOrNull {
                it.appName.equals(experience.name, ignoreCase = true)
            } ?: apps.firstOrNull()
                ?: throw IOException("Body has no apps in its Sunshine app list")

            writeStreamPreferences(experience, host)

            val details = http.getComputerDetails(serverInfo)
            val intent = buildGameIntent(host, http.getHttpsPort(serverInfo), app, details, serverCert)

            // One logcat line per launch so a session is diagnosable from
            // adb alone (the connection callbacks log under the same tag).
            val mesh = isMeshHost(host)
            Log.i(
                "HydraStream",
                "launching stream: host=$host route=${if (mesh) "mesh" else "lan"}" +
                    " packetSize=${if (mesh) 1024 else 1392}" +
                    " bitrateKbps=${if (mesh) BITRATE_WIREGUARD_KBPS else BITRATE_LAN_KBPS}" +
                    " app=${app.appName}" +
                    " res=${experience.streamWidth}x${experience.streamHeight}" +
                    " fps=$STREAM_FPS"
            )
            appContext.startActivity(intent)

            // Established from Hydra's point of view: pairing done, session
            // launch handed to Game. TODO(#544): report first-frame timing
            // by observing the connection, without touching upstream Game.
            state.onStreamEstablished(host, experience, bodyId, serverCert, selfService)
        } catch (e: Exception) {
            Log.w(TAG, "launchStream failed: ${e.message}")
            state.onStreamFailed("Could not start ${experience.label}: ${e.message}")
        }
    }

    /**
     * Tear the stream down by bringing the catalog task to the front. Game
     * and GameXR declare noHistory, so losing the foreground finishes them,
     * and Game.onStop stops the NvConnection. This needs no upstream hooks.
     */
    override fun stopStream() {
        expectedStop.set(true)
        val intent = Intent(appContext, HydraCatalogActivity::class.java)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        appContext.startActivity(intent)
    }

    // ------------------------------------------------------------------
    // Stream end detection (called by HydraApp lifecycle callbacks)
    // ------------------------------------------------------------------

    /**
     * A Game or GameXR activity was destroyed. When this class did not ask
     * for the stop itself, the stream ended on its own: user exit inside
     * the stream, or a terminated connection.
     */
    fun onGameActivityDestroyed() {
        if (expectedStop.getAndSet(false)) {
            return
        }
        state.onStreamEnded()
    }

    // ------------------------------------------------------------------
    // Pairing
    // ------------------------------------------------------------------

    /**
     * One pairing attempt. Generates the PIN, schedules the Sunshine PIN
     * post for 0.3 s later (the PIN is only accepted while the
     * getservercert request is pending), and runs the blocking pair call
     * with a watchdog so a lost PIN post cannot hang the state machine.
     */
    private fun pairWithPin(
        http: NvHTTP,
        serverInfo: String,
        host: String,
        headConfig: HeadConfig
    ): PairingManager.PairState {
        val pin = PairingManager.generatePinString()

        val pinThread = Thread({
            try {
                Thread.sleep(HydraState.PIN_POST_DELAY_MS)
                state.postPinToSunshine(
                    host,
                    headConfig.effectiveSunshineUsername,
                    headConfig.effectiveSunshinePassword,
                    pin
                )
            } catch (e: Exception) {
                Log.w(TAG, "Sunshine PIN post failed: ${e.message}")
            }
        }, "HydraPinPost")
        pinThread.isDaemon = true
        pinThread.start()

        val result = arrayOfNulls<PairingManager.PairState>(1)
        val error = arrayOfNulls<Exception>(1)
        val pairThread = Thread({
            try {
                result[0] = http.pairingManager.pair(serverInfo, pin)
            } catch (e: Exception) {
                error[0] = e
            }
        }, "HydraPair")
        pairThread.isDaemon = true
        pairThread.start()
        pairThread.join(PAIR_TIMEOUT_MS)

        if (pairThread.isAlive) {
            // Abandon the hung attempt; the daemon thread dies with the
            // socket. Sunshine drops the pending pair on its side.
            throw IOException("Pairing timed out after ${PAIR_TIMEOUT_MS / 1000} s")
        }
        error[0]?.let { e ->
            throw if (e is IOException) e else IOException("Pairing failed: ${e.message}", e)
        }
        return result[0] ?: throw IOException("Pairing produced no result")
    }

    /** Unpair, ignoring failures: re-pairing regardless is the contract. */
    private fun tryUnpair(http: NvHTTP) {
        try {
            http.unpair()
        } catch (e: Exception) {
            Log.w(TAG, "unpair failed (continuing to re-pair): ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Launch
    // ------------------------------------------------------------------

    /**
     * Game reads resolution, fps, and bitrate from the default shared
     * preferences, not from the intent, so write them there first.
     * Portrait experiences stream 1080x1920, everything else 1920x1080
     * (Experience.streamWidth/Height). Bitrate: 150000 kbps on the venue
     * LAN, 20000 kbps on a 10.10.* WireGuard host.
     */
    @Suppress("DEPRECATION")
    private fun writeStreamPreferences(experience: Experience, host: String) {
        val bitrate = if (isMeshHost(host)) BITRATE_WIREGUARD_KBPS else BITRATE_LAN_KBPS
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
        prefs.edit()
            .putString(PREF_RESOLUTION, "${experience.streamWidth}x${experience.streamHeight}")
            .putString(PREF_FPS, STREAM_FPS.toString())
            .putInt(PREF_BITRATE_KBPS, bitrate)
            .apply()
    }

    /**
     * The same intent ServerHelper.createStartIntent builds. GameXR (the
     * immersive entry, com.oculus.intent.category.VR) when VR mode is on,
     * which is the default on headsets; plain Game otherwise. Always a new
     * task: we start from the application context, and the stream must not
     * fall when the catalog task changes.
     */
    private fun buildGameIntent(
        host: String,
        httpsPort: Int,
        app: NvApp,
        details: ComputerDetails,
        serverCert: X509Certificate?
    ): Intent {
        val vrMode = PreferenceConfiguration.readPreferences(appContext).enableVrMode
        val intent = if (vrMode) {
            Intent(appContext, GameXR::class.java).apply {
                addCategory("com.oculus.intent.category.VR")
            }
        } else {
            Intent(appContext, Game::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(Game.EXTRA_HOST, host)
        // Mesh hosts stream with Moonlight's remote profile; see isMeshHost.
        intent.putExtra(Game.EXTRA_HYDRA_REMOTE_TUNED, isMeshHost(host))
        intent.putExtra(Game.EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT)
        intent.putExtra(Game.EXTRA_HTTPS_PORT, httpsPort)
        intent.putExtra(Game.EXTRA_APP_NAME, app.appName)
        intent.putExtra(Game.EXTRA_APP_ID, app.appId)
        intent.putExtra(Game.EXTRA_APP_HDR, false)
        intent.putExtra(Game.EXTRA_UNIQUEID, HydraApp.from(appContext).moonlightClientId)
        intent.putExtra(Game.EXTRA_PC_UUID, details.uuid)
        intent.putExtra(Game.EXTRA_PC_NAME, details.name)
        if (serverCert != null) {
            try {
                intent.putExtra(Game.EXTRA_SERVER_CERT, serverCert.encoded)
            } catch (e: Exception) {
                Log.w(TAG, "could not encode server cert for intent: ${e.message}")
            }
        }
        return intent
    }
}
