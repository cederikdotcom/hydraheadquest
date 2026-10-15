package com.limelight.hydra

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * XrHooks against the WiVRn client app (issues #739, #734).
 *
 * WiVRn is a peer of ALVR, not a replacement for this kiosk: both are VR
 * drivers, and Sunshine/Moonlight remains the flat lane. Which driver a
 * session uses is decided by the body, which advertises what it can serve
 * in xr_drivers, and by the head, which speaks what it has installed.
 *
 * The two drivers dial in OPPOSITE DIRECTIONS, which is why this class is
 * not a copy of [AlvrLauncher]:
 * - ALVR: the body holds the client's hostname and dials the headset, so
 *   the client only has to be running.
 * - WiVRn: the CLIENT dials the server, so it needs the body's host and
 *   the pairing PIN before it can do anything useful.
 *
 * The client takes both in a URI, parsed by application::set_server_uri in
 * the WiVRn fork:
 *
 *     wivrn://[:PIN@]host[:port]     UDP, the normal case
 *     wivrn+tcp://[:PIN@]host[:port] TCP only
 *
 * The PIN sits in the URI's password position and the port defaults to
 * 9757. Launching with no URI is still valid: the client then falls back to
 * its own avahi discovery and whatever it has already paired with.
 *
 * Launch facts:
 * - The entry point is <package>/org.meumeu.wivrn.MainActivity. The Java
 *   package stays org.meumeu.* even when the applicationId is ours, so the
 *   activity name does not move with the package id, and the same activity
 *   name works for our build and the upstream one.
 * - The intent MUST carry category com.oculus.intent.category.VR, for the
 *   same vrshell reason ALVR does.
 * - The intent sets an EXPLICIT component. That matters here: the client's
 *   VIEW intent-filter declares DEFAULT and BROWSABLE but NOT the VR
 *   category, so an implicit VIEW intent carrying VR would match nothing.
 *   An explicit component skips filter matching entirely.
 * - The launch must come from a foreground activity, as with ALVR.
 *
 * Package visibility for Android 11+ is declared in AndroidManifest.xml for
 * every candidate package.
 */
class WivrnLauncher(
    context: Context,
    private val candidatePackages: List<String> = CANDIDATE_PACKAGES,
) : HydraState.XrHooks {

    companion object {
        private const val TAG = "WivrnLauncher"

        /**
         * The Hydra-signed WiVRn client (#739). Named hydrawivrn, not a
         * generic "xr" name: WiVRn is the only VR client we build, since
         * ALVR is launched as the stock upstream app, so there is no unified
         * client that an "xr" name would describe.
         */
        const val OUR_PACKAGE = "com.experiencenet.hydrawivrn"

        /**
         * The debug-signed upstream build side-loaded for the first PyroWave
         * tests. A FALLBACK until the signed hydrawivrn client (#739) ships,
         * so the lane can be exercised on a dev headset now. Our own client
         * always wins when both are present.
         */
        const val UPSTREAM_PACKAGE = "org.meumeu.wivrn.github.testing"

        /** Preference order: our signed client first, upstream test build second. */
        val CANDIDATE_PACKAGES = listOf(OUR_PACKAGE, UPSTREAM_PACKAGE)

        /** Entry activity. The Java package does not follow the app id. */
        const val WIVRN_ACTIVITY = "org.meumeu.wivrn.MainActivity"

        /** vrshell rejects VR placement without this category. */
        const val CATEGORY_VR = "com.oculus.intent.category.VR"

        /** WiVRn's default control and stream port. */
        const val DEFAULT_PORT = 9757

        /**
         * Build the URI the client dials. The PIN goes in the password
         * position, which is what set_server_uri reads.
         */
        fun serverUri(host: String, pin: String? = null, port: Int = DEFAULT_PORT): String {
            val credentials = if (pin.isNullOrEmpty()) "" else ":$pin@"
            return "wivrn://$credentials$host:$port"
        }
    }

    private val appContext: Context = context.applicationContext

    override val driverName: String = XrDrivers.WIVRN

    /**
     * The first candidate package actually installed, in preference order,
     * or null when none is present. Our signed client wins over the upstream
     * test build.
     */
    private fun resolvedPackage(): String? {
        val pm = appContext.packageManager
        for (pkg in candidatePackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: PackageManager.NameNotFoundException) {
                // not this one; try the next candidate
            } catch (e: Exception) {
                Log.w(TAG, "WiVRn package query failed for $pkg: ${e.message}")
            }
        }
        return null
    }

    override fun isXrClientInstalled(): Boolean = resolvedPackage() != null

    override fun launchXrClient(): Boolean = launchXrClient(null, null)

    /**
     * Launch the client, pointing it at [bodyHost] with [pin] when both are
     * known. With neither, the client falls back to its own discovery.
     */
    fun launchXrClient(bodyHost: String?, pin: String?): Boolean {
        val pkg = resolvedPackage()
        if (pkg == null) {
            Log.w(TAG, "no WiVRn client installed (looked for $candidatePackages)")
            return false
        }
        val activity = HydraApp.from(appContext).currentActivity
        if (activity == null) {
            Log.w(TAG, "no foreground activity; vrshell would reject the VR launch")
            return false
        }
        return try {
            val intent = Intent().apply {
                component = ComponentName(pkg, WIVRN_ACTIVITY)
                addCategory(CATEGORY_VR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!bodyHost.isNullOrEmpty()) {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(serverUri(bodyHost, pin))
                }
            }
            activity.startActivity(intent)
            // Never log the URI itself: the PIN is in it.
            Log.i(TAG, "launched WiVRn client ($pkg)" + if (bodyHost.isNullOrEmpty()) "" else " -> $bodyHost")
            true
        } catch (e: Exception) {
            Log.w(TAG, "WiVRn launch failed: ${e.message}")
            false
        }
    }
}
