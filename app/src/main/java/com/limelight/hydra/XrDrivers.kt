package com.limelight.hydra

import android.content.Context
import android.util.Log

/**
 * Which VR drivers this headset can speak, and the launcher for each.
 *
 * The fleet model (ruled 2026-09-16): alvr, wivrn and picobusinessstreaming
 * are PEERS, all of them VR drivers. Sunshine and Moonlight are the FLAT
 * lane and are not drivers at all, which is why nothing here mentions them.
 *
 * A body advertises what it can serve in xr_drivers; a head speaks what it
 * has installed. The cluster matches the two, so this class exists to
 * answer one question honestly: what does this headset actually have?
 *
 * WiVRn is preferred where both are present. It is ours, it carries the
 * PyroWave codec, and its server runs on Linux bodies. ALVR remains the
 * answer for a Windows body, since WiVRn's server is Monado-based and does
 * not run there.
 */
object XrDrivers {

    private const val TAG = "XrDrivers"

    const val WIVRN = "wivrn"
    const val ALVR = "alvr"

    /**
     * Preference order. First installed wins, so a headset carrying both
     * uses WiVRn unless the session explicitly asks for the other.
     */
    private val PREFERENCE = listOf(WIVRN, ALVR)

    /** Every driver this headset can actually speak, in preference order. */
    fun installed(context: Context): List<String> =
        PREFERENCE.filter { launcherFor(context, it)?.isXrClientInstalled() == true }

    /** The launcher for one driver, or null when the name is unknown. */
    fun launcherFor(context: Context, driver: String?): HydraState.XrHooks? = when (driver) {
        WIVRN -> WivrnLauncher(context)
        ALVR -> AlvrLauncher(context)
        else -> null
    }

    /**
     * The launcher to use for a session. [sessionDriver] is what the
     * cluster recorded when the session was created; falling back to the
     * preferred installed driver keeps an older cluster, which records no
     * driver at all, working exactly as before.
     */
    fun launcher(context: Context, sessionDriver: String?): HydraState.XrHooks? {
        launcherFor(context, sessionDriver)?.let { chosen ->
            if (chosen.isXrClientInstalled()) return chosen
            Log.w(TAG, "session asked for $sessionDriver but its client is not installed")
            return null
        }
        val fallback = installed(context).firstOrNull()
        if (fallback == null) {
            Log.w(TAG, "no VR driver client installed on this headset")
            return null
        }
        if (sessionDriver != null) {
            Log.w(TAG, "unknown driver $sessionDriver; using $fallback")
        }
        return launcherFor(context, fallback)
    }
}
