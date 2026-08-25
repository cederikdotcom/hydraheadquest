package com.limelight.hydra

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import com.limelight.Game
import com.limelight.computers.IdentityManager
import com.limelight.preferences.PreferenceConfiguration

/**
 * Application subclass owning the Hydra head state machine.
 *
 * Registered in AndroidManifest.xml on the application tag. On process
 * start it builds the HydraState singleton, wires the StreamHooks
 * implementation, the screenshot provider, and the Moonlight client id,
 * then starts the machine: with a stored enrollment the 30 s tick and the
 * 3 s command poll begin at once, without one the head sits unconfigured
 * until the enrollment activity saves a config.
 *
 * Also tracks activities so that:
 * - the remote screenshot command captures whatever is currently on screen
 * - a destroyed Game/GameXR activity is reported as stream end
 */
class HydraApp : Application() {

    companion object {
        private const val TAG = "HydraApp"

        /** The HydraApp instance behind any context. */
        fun from(context: Context): HydraApp =
            context.applicationContext as HydraApp

        /** Shorthand for the state machine behind any context. */
        fun state(context: Context): HydraState = from(context).hydraState
    }

    lateinit var hydraState: HydraState
        private set

    lateinit var streamHooks: HydraStreamHooks
        private set

    /** The Hydra mesh tunnel (issue #544 Phase 2). */
    lateinit var hydraWireGuard: HydraWireGuard
        private set

    /** The currently resumed activity, for screenshot capture. */
    @Volatile
    var currentActivity: Activity? = null
        private set

    /**
     * The Moonlight unique client id (IdentityManager uid), reported in
     * heartbeat diagnostics and passed to Game as EXTRA_UNIQUEID. Note the
     * pairing HTTP layer hardcodes 0123456789ABCDEF internally, which is
     * what the unpair call must and does use.
     */
    val moonlightClientId: String? by lazy {
        try {
            IdentityManager(this).uniqueId
        } catch (e: Exception) {
            Log.w(TAG, "could not load Moonlight client id: ${e.message}")
            null
        }
    }

    override fun onCreate() {
        super.onCreate()

        seedVrDefaults()

        hydraWireGuard = HydraWireGuard(this)
        hydraState = HydraState(this, HydraConfigStore(this))
        streamHooks = HydraStreamHooks(this, hydraState)
        hydraState.streamHooks = streamHooks
        hydraState.moonlightClientIdProvider = { moonlightClientId }
        hydraState.wireguardStatusProvider = { hydraWireGuard.statusString() }
        hydraState.screenshotProvider = {
            currentActivity?.let { HydraScreenshot.capture(it) }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }

            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                // GameXR extends Game, so this covers both stream entries.
                if (activity is Game) {
                    streamHooks.onGameActivityDestroyed()
                }
            }
        })

        // With a stored enrollment this starts the timers immediately;
        // otherwise the head waits in unconfigured for enrollment.
        hydraState.start()

        // A stored WireGuard config only exists after an enrolled head
        // fetched it via the operator menu. Bring the tunnel back up in
        // the background when the one-time VPN consent is already granted
        // (prepareIntent null). With consent missing the status reads
        // consent-needed and an operator must use the WireGuard action.
        Thread({
            try {
                if (hydraWireGuard.hasStoredConfig() &&
                    hydraWireGuard.prepareIntent() == null
                ) {
                    hydraWireGuard.bringUpStored()
                }
            } catch (t: Throwable) {
                // Never let the tunnel take the kiosk down (Horizon OS
                // support is exactly what Phase 2 tests).
                Log.w(TAG, "wireguard auto bring-up failed: ${t.message}")
            }
        }, "HydraWgStart").start()
    }

    /**
     * Seed the XR streaming defaults on first run, before the renderer
     * or the settings page read them. Each key is seeded only while it
     * is absent, so a later user choice (settings or in-stream picker)
     * always wins and is never overwritten.
     *
     * - vr_environment 2: the generated Ambient Dusk gradient, picker
     *   cell 2 (0 is passthrough, 1 the black void, 2 and up photos).
     *   XrRenderer registers the generated preset as the first photo
     *   cell. It is a 1024x512 texture, about 1/32 of the 4096x2048
     *   photos that chopped the stream on the Quest 2, so the head
     *   gets a dimly lit room at close to void cost.
     * - list_vr_depth_source "off": upstream defaults to "model", the
     *   MiDaS depth net. It doubles the video swapchain width for
     *   synthesized stereo and warps each eye by an aging depth map.
     *   On the Quest 2 that is GPU budget we do not have, and the warp
     *   shows as reprojection artifacts when the head turns.
     * - seekbar_vr_curvature 100: shows the screen as a cylinder
     *   compositor layer with radius equal to the screen distance, the
     *   usual VR media screen wrap. Upstream falls back to the flat
     *   quad by itself on runtimes without
     *   XR_KHR_composition_layer_cylinder.
     */
    private fun seedVrDefaults() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val edit = prefs.edit()
        if (!prefs.contains(PreferenceConfiguration.VR_ENVIRONMENT_PREF_STRING)) {
            edit.putInt(PreferenceConfiguration.VR_ENVIRONMENT_PREF_STRING, 2)
        } else if (prefs.getInt("hydra_seed_version", 1) < 2 &&
            prefs.getInt(PreferenceConfiguration.VR_ENVIRONMENT_PREF_STRING, -1) == 1
        ) {
            // One-time migration: v0.4.1 seeded the black void (1). Move
            // those devices to Ambient Dusk (2) unless the user picked a
            // different environment themselves (value != 1).
            edit.putInt(PreferenceConfiguration.VR_ENVIRONMENT_PREF_STRING, 2)
        }
        edit.putInt("hydra_seed_version", 2)
        if (!prefs.contains(PreferenceConfiguration.VR_DEPTH_SOURCE_PREF_STRING)) {
            edit.putString(PreferenceConfiguration.VR_DEPTH_SOURCE_PREF_STRING, "off")
        }
        if (!prefs.contains(PreferenceConfiguration.VR_CURVATURE_PREF_STRING)) {
            edit.putInt(PreferenceConfiguration.VR_CURVATURE_PREF_STRING, 100)
        }
        edit.apply()
    }
}
