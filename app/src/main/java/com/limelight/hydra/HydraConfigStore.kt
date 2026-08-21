package com.limelight.hydra

import android.content.Context
import android.content.SharedPreferences
import com.limelight.hydra.model.EnrollmentConfig

/**
 * SharedPreferences persistence for the enrollment result.
 *
 * Stores server_url, head_id, and the per-device token in the "hydra" prefs.
 * The token grants head-scoped API access; never log it.
 */
class HydraConfigStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "hydra"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_HEAD_ID = "head_id"
        private const val KEY_TOKEN = "token"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Load the stored enrollment, or null when the head is not enrolled. */
    fun load(): EnrollmentConfig? {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: return null
        val headId = prefs.getString(KEY_HEAD_ID, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        if (serverUrl.isEmpty() || headId.isEmpty() || token.isEmpty()) {
            return null
        }
        return EnrollmentConfig(serverUrl, headId, token)
    }

    fun save(config: EnrollmentConfig) {
        prefs.edit()
            .putString(KEY_SERVER_URL, config.serverUrl)
            .putString(KEY_HEAD_ID, config.headId)
            .putString(KEY_TOKEN, config.token)
            .apply()
    }

    /** Reset: forget the enrollment. The head returns to unconfigured. */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
