package com.limelight.hydra

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The Hydra launcher entry point.
 *
 * A second MAIN/LAUNCHER activity next to upstream's PcView, so the stock
 * Moonlight UI stays reachable for debugging during Phase 1. This one
 * routes by enrollment: unconfigured heads go to the enrollment screen,
 * enrolled heads go to the kiosk catalog.
 *
 * The activity stays alive underneath the enrollment screen. When the
 * operator finishes enrolling and backs out, onResume routes again and
 * lands on the catalog, so the enrollment activity itself needs no
 * navigation code.
 *
 * To make Hydra the sole launcher later, remove the LAUNCHER category from
 * PcView's intent-filter in AndroidManifest.xml. Everything else keeps
 * working: PcView stays exported and reachable by explicit intent.
 */
class HydraLaunchActivity : Activity() {

    private var routedToEnrollment = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No UI of its own; it only routes.
    }

    override fun onResume() {
        super.onResume()
        val store = HydraConfigStore(this)
        if (store.load() != null) {
            HydraApp.state(this).ensureStarted()
            startActivity(Intent(this, HydraCatalogActivity::class.java))
            finish()
        } else if (!routedToEnrollment) {
            routedToEnrollment = true
            startActivity(Intent(this, HydraEnrollmentActivity::class.java))
        } else {
            // Back from enrollment while still unenrolled: leave the app
            // instead of bouncing the operator into enrollment forever.
            finish()
        }
    }
}
