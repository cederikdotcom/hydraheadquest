package com.limelight.hydra

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * XrHooks against the separate ALVR client app (issue #558).
 *
 * The ALVR client is its own APK, deployed via HMS next to this kiosk;
 * it is never merged into this app (issue #544 phase 3). This class only
 * checks that it is installed and launches it.
 *
 * Launch facts, verified on hardware (issue #544, 2026-08-25/26):
 * - The entry point is alvr.client.stable/android.app.NativeActivity.
 * - The intent MUST carry category com.oculus.intent.category.VR;
 *   without it vrshell rejects the placement.
 * - The launch must come from a foreground activity. A background or
 *   application context launch is refused by vrshell, so a missing
 *   foreground activity is reported as a launch failure, never retried
 *   blindly.
 *
 * The package visibility query for alvr.client.stable is declared in
 * AndroidManifest.xml (Android 11+ filtering).
 */
class AlvrLauncher(context: Context) : HydraState.XrHooks {

    companion object {
        private const val TAG = "AlvrLauncher"

        /** The ALVR client package (stable channel, no suffix). */
        const val ALVR_PACKAGE = "alvr.client.stable"

        /** The ALVR client entry activity (a NativeActivity). */
        const val ALVR_ACTIVITY = "android.app.NativeActivity"

        /** vrshell rejects VR placement without this category. */
        const val CATEGORY_VR = "com.oculus.intent.category.VR"
    }

    private val appContext: Context = context.applicationContext

    override fun isXrClientInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(ALVR_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "ALVR package query failed: ${e.message}")
            false
        }
    }

    override fun launchXrClient(): Boolean {
        val activity = HydraApp.from(appContext).currentActivity
        if (activity == null) {
            Log.w(TAG, "no foreground activity; vrshell would reject the VR launch")
            return false
        }
        return try {
            val intent = Intent().apply {
                component = ComponentName(ALVR_PACKAGE, ALVR_ACTIVITY)
                addCategory(CATEGORY_VR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            Log.i(TAG, "launched ALVR client")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ALVR client launch failed: ${e.message}")
            false
        }
    }
}
