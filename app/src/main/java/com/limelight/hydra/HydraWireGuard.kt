package com.limelight.hydra

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.nio.charset.StandardCharsets

/**
 * The Hydra mesh tunnel, wrapping the official wireguard-android GoBackend
 * (Go userspace WireGuard over VpnService, issue #544 Phase 2).
 *
 * One tunnel named "hydra". The wg-quick config comes ready-made from
 * hydracluster (HydraClusterClient.getWireguardConfig) with this head's
 * private key embedded; the head never generates keys. The config is stored
 * in an app-private file (wg-hydra.conf in filesDir) and reused across
 * launches. Never log the config text.
 *
 * Horizon OS support is exactly what Phase 2 tests, so every backend call
 * is wrapped: a failing backend surfaces as an error status string, never
 * as an app crash. The GoBackend is created lazily because its constructor
 * loads the native wg-go library, which may not load on every OS build.
 *
 * bringUp and takeDown block on the backend; call them off the main thread.
 * The first bring-up needs the system VPN consent dialog: fire
 * [prepareIntent] via startActivityForResult first. Once consent is granted
 * (prepareIntent returns null) the tunnel comes up without UI, and HydraApp
 * auto-brings it up on process start.
 */
class HydraWireGuard(context: Context) {

    companion object {
        private const val TAG = "HydraWireGuard"

        /** The single tunnel name (Tunnel.NAME_MAX_LENGTH is 15). */
        const val TUNNEL_NAME = "hydra"

        /** App-private config file in Context.filesDir. */
        const val CONFIG_FILE_NAME = "wg-hydra.conf"

        /**
         * Interface MTU applied at parse time. The hydraguard config has
         * no MTU line (the contract says not to add one to the stored
         * text), and GoBackend then brings the tun up with its 1280
         * default. 1420 is the WireGuard-over-IPv4 convention (1500 minus
         * 80 bytes of overhead) that the Windows bodies on this mesh use.
         * Setting it aligns the head with the rest of the fleet and
         * removes one variable from stream debugging. Verified on
         * hardware 2026-08-25: 1420-byte inner packets traverse the
         * tunnel with 0% loss, so this is convention alignment, not a
         * packet-drop fix.
         */
        const val TUNNEL_MTU = 1420

        /** Cap for error text in the heartbeat diagnostics value. */
        private const val ERROR_TRUNCATE = 60
    }

    private val appContext: Context = context.applicationContext

    private val configFile: File
        get() = File(appContext.filesDir, CONFIG_FILE_NAME)

    /** Lazily created; null when the native backend failed to load. */
    private var backend: GoBackend? = null
    private var backendFailed = false

    /** Last backend or parse failure, for diagnostics. Never config text. */
    @Volatile
    private var lastError: String? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            Log.i(TAG, "tunnel state: $newState")
        }
    }

    @Synchronized
    private fun backendOrNull(): GoBackend? {
        if (backend == null && !backendFailed) {
            try {
                // Loads libwg-go.so; may fail on an unsupported OS build.
                backend = GoBackend(appContext)
            } catch (t: Throwable) {
                backendFailed = true
                lastError = "backend init: ${t.message ?: t.javaClass.simpleName}"
                Log.w(TAG, "GoBackend unavailable: ${t.message}")
            }
        }
        return backend
    }

    // ------------------------------------------------------------------
    // Config persistence
    // ------------------------------------------------------------------

    fun hasStoredConfig(): Boolean = configFile.length() > 0

    /** Write the fetched config app-private. Never log the text. */
    fun storeConfig(configText: String) {
        appContext.openFileOutput(CONFIG_FILE_NAME, Context.MODE_PRIVATE).use {
            it.write(configText.toByteArray(StandardCharsets.UTF_8))
        }
        Log.i(TAG, "stored wireguard config (${configText.length} bytes)")
    }

    private fun loadStoredConfig(): String? {
        return try {
            if (!hasStoredConfig()) null
            else configFile.readText(StandardCharsets.UTF_8).ifBlank { null }
        } catch (t: Throwable) {
            lastError = "config read: ${t.message ?: t.javaClass.simpleName}"
            null
        }
    }

    // ------------------------------------------------------------------
    // Tunnel control
    // ------------------------------------------------------------------

    /**
     * The system VPN consent intent, or null when consent is already
     * granted. Non-null must be fired with startActivityForResult; bring
     * the tunnel up only after RESULT_OK.
     */
    fun prepareIntent(): Intent? {
        return try {
            VpnService.prepare(appContext)
        } catch (t: Throwable) {
            // Some OS builds may not implement VpnService at all.
            lastError = "vpn prepare: ${t.message ?: t.javaClass.simpleName}"
            null
        }
    }

    /** True when the backend reports the hydra tunnel as up. */
    fun isUp(): Boolean {
        val b = backendOrNull() ?: return false
        return try {
            b.getState(tunnel) == Tunnel.State.UP
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Store the config, then bring the tunnel up. Returns true on success.
     * Blocking; never call on the main thread.
     */
    fun bringUp(configText: String): Boolean {
        try {
            storeConfig(configText)
        } catch (t: Throwable) {
            lastError = "config write: ${t.message ?: t.javaClass.simpleName}"
            return false
        }
        return connect(configText)
    }

    /** Bring the tunnel up from the stored config file, if present. */
    fun bringUpStored(): Boolean {
        val text = loadStoredConfig() ?: return false
        return connect(text)
    }

    /**
     * The config text with an explicit MTU line in its [Interface]
     * section. Idempotent: a config that already carries an MTU line is
     * returned unchanged. The stored file keeps the exact text the
     * cluster served (contract section 12); the MTU is a client-side,
     * parse-time addition only. Text injection is preferred over
     * rebuilding via Config.Builder/Interface.Builder because it does
     * not depend on the builder API surface of the wireguard-android
     * version in use.
     */
    private fun withInterfaceMtu(configText: String): String {
        if (Regex("(?im)^\\s*MTU\\s*=").containsMatchIn(configText)) {
            return configText
        }
        val lines = configText.lines().toMutableList()
        val idx = lines.indexOfFirst {
            it.trim().equals("[Interface]", ignoreCase = true)
        }
        if (idx < 0) {
            // Malformed config; let Config.parse report the real problem.
            return configText
        }
        lines.add(idx + 1, "MTU = $TUNNEL_MTU")
        return lines.joinToString("\n")
    }

    private fun connect(configText: String): Boolean {
        val b = backendOrNull()
        if (b == null) {
            Log.w(TAG, "bring-up skipped, backend unavailable")
            return false
        }
        return try {
            val config = BufferedReader(StringReader(withInterfaceMtu(configText))).use {
                Config.parse(it)
            }
            b.setState(tunnel, Tunnel.State.UP, config)
            lastError = null
            Log.i(TAG, "tunnel up")
            // Pin the process while the tunnel is up: without this,
            // Android reaps the backgrounded kiosk (and the tunnel)
            // whenever another app, e.g. the ALVR client, is fullscreen.
            HydraTunnelService.start(appContext)
            true
        } catch (t: Throwable) {
            // BadConfigException, BackendException (VPN_NOT_AUTHORIZED,
            // UNABLE_TO_START_VPN, ...), or a native failure. Never rethrow:
            // a broken tunnel must not take the kiosk down.
            lastError = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "tunnel bring-up failed: ${t.message}")
            false
        }
    }

    /** Take the tunnel down. Blocking; never call on the main thread. */
    fun takeDown(): Boolean {
        val b = backendOrNull() ?: return false
        return try {
            b.setState(tunnel, Tunnel.State.DOWN, null)
            Log.i(TAG, "tunnel down")
            HydraTunnelService.stop(appContext)
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "tunnel take-down failed: ${t.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /**
     * Seconds since the newest peer handshake, or null when the backend
     * reports no handshake yet (latestHandshakeEpochMillis == 0).
     */
    private fun handshakeAgeSeconds(): Long? {
        val b = backendOrNull() ?: return null
        return try {
            val stats = b.getStatistics(tunnel)
            val latest = stats.peers()
                .mapNotNull { stats.peer(it)?.latestHandshakeEpochMillis }
                .maxOrNull() ?: 0L
            if (latest == 0L) null
            else (System.currentTimeMillis() - latest) / 1000
        } catch (t: Throwable) {
            null
        }
    }

    /** Human-readable handshake line for the diagnostics view. */
    fun lastHandshakeDescription(): String {
        if (!isUp()) return "tunnel not up"
        val age = handshakeAgeSeconds() ?: return "no handshake"
        return "last handshake ${age}s ago"
    }

    /**
     * The heartbeat diagnostics.wireguard value:
     * - "disabled": no stored config
     * - "consent-needed": config stored, VPN consent not granted yet
     * - "up no-handshake": tunnel up, no peer handshake yet
     * - "up hs <n>s": tunnel up, newest handshake n seconds ago
     * - "error: <msg>": bring-up or backend failure, truncated
     * - "down": config stored and consented, tunnel not up (transient)
     */
    fun statusString(): String {
        if (!hasStoredConfig()) return "disabled"
        if (prepareIntent() != null) return "consent-needed"
        if (isUp()) {
            val age = handshakeAgeSeconds() ?: return "up no-handshake"
            return "up hs ${age}s"
        }
        val error = lastError
        if (error != null) return "error: ${error.take(ERROR_TRUNCATE)}"
        return "down"
    }
}
