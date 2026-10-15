package com.limelight.hydra

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Per-head GameStream identity (issues #531, #549).
 *
 * Sunshine keys its pending-pair session by the client uniqueid, and a
 * 2026.914 body rejects a duplicate uniqueid outright. Historically every
 * Moonlight client paired as the same hardcoded 0123456789ABCDEF with
 * devicename "roth", so one head's abandoned pairing session poisoned the
 * next head's pair, and no body could tell two heads apart.
 *
 * Each head now derives its own identity from its head id. This mirrors
 * hydraheadflatscreen's gamestreamIdentity (committed there as d485de1)
 * EXACTLY, so every head across the fleet uses one scheme and bodies see
 * distinct, stable identities:
 *
 *   uniqueid   = first 8 bytes of SHA-256(head id), 16 UPPERCASE hex
 *   devicename = the head id verbatim
 *
 * A head with no id (not yet enrolled) falls back to the legacy shared
 * values so it can still pair.
 */
object HydraIdentity {

    /** Legacy shared values, used only when the head id is unknown. */
    const val LEGACY_UNIQUE_ID = "0123456789ABCDEF"
    const val LEGACY_DEVICE_NAME = "roth"

    private const val HEX = "0123456789ABCDEF"

    /**
     * uniqueid = first 8 bytes of SHA-256(headId) as 16 uppercase hex.
     * Falls back to [LEGACY_UNIQUE_ID] when headId is null or empty.
     * Manual hex (no String.format) to stay clear of the ART verifier
     * issues this app has hit with vararg formatting.
     */
    fun gamestreamUniqueId(headId: String?): String {
        if (headId.isNullOrEmpty()) return LEGACY_UNIQUE_ID
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(headId.toByteArray(StandardCharsets.UTF_8))
        val out = CharArray(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xFF
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }

    /** devicename = the head id verbatim, or [LEGACY_DEVICE_NAME] when unknown. */
    fun gamestreamDeviceName(headId: String?): String =
        if (headId.isNullOrEmpty()) LEGACY_DEVICE_NAME else headId
}
