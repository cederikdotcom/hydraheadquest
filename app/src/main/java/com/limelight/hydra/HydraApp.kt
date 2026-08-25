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

        seedVrEnvironmentDefault()

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
     * Seed the XR streaming environment to the black void on first run.
     *
     * Upstream XrRenderer defaults an unset "vr_environment" preference
     * to the first 4096x2048 equirect photo. That extra composited
     * background layer is too heavy for the Quest 2 GPU while it also
     * decodes a 1080p60 stream: the stream visibly chops. The void
     * (picker cell 1) submits no background layer at all, so it is the
     * lightest option, cheaper than passthrough too.
     *
     * Seeding the preference here keeps upstream code untouched. The
     * in-stream environment picker still works: a user pick overwrites
     * this value, and we never seed again once the key exists.
     */
    private fun seedVrEnvironmentDefault() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.contains(PreferenceConfiguration.VR_ENVIRONMENT_PREF_STRING)) {
            // 1 = XrRenderer CELL_VOID (0 is passthrough, 2 and up are photos).
            prefs.edit()
                .putInt(PreferenceConfiguration.VR_ENVIRONMENT_PREF_STRING, 1)
                .apply()
        }
    }
}
