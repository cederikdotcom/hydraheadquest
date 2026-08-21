package com.limelight.hydra

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.limelight.Game
import com.limelight.computers.IdentityManager

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

        hydraState = HydraState(this, HydraConfigStore(this))
        streamHooks = HydraStreamHooks(this, hydraState)
        hydraState.streamHooks = streamHooks
        hydraState.moonlightClientIdProvider = { moonlightClientId }
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
    }
}
