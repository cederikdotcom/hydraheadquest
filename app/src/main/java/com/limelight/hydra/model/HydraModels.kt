package com.limelight.hydra.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data models for the Hydra head API.
 *
 * Field names follow the wire contract in docs/hydra-api-contract.md.
 * All parsing is hand rolled on org.json so no new dependency is needed.
 */

/** Payload of the fleet enrollment QR code. */
data class FleetEnrollQR(
    val serverUrl: String,
    val enrollmentToken: String
) {
    companion object {
        /** Parse the QR JSON. Returns null when a required field is missing. */
        fun fromJson(json: String): FleetEnrollQR? {
            return try {
                val obj = JSONObject(json)
                val url = obj.optString("server_url", "")
                val token = obj.optString("enrollment_token", "")
                if (url.isEmpty() || token.isEmpty()) null
                else FleetEnrollQR(url, token)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Persistent result of enrollment. All three fields are required. */
data class EnrollmentConfig(
    val serverUrl: String,
    val headId: String,
    val token: String
) {
    /** Base URL with trailing slashes stripped. */
    val baseUrl: String
        get() = serverUrl.trimEnd('/')

    companion object {
        fun fromJson(json: String): EnrollmentConfig? {
            return try {
                val obj = JSONObject(json)
                val url = obj.optString("server_url", "")
                val headId = obj.optString("head_id", "")
                val token = obj.optString("token", "")
                if (url.isEmpty() || headId.isEmpty() || token.isEmpty()) null
                else EnrollmentConfig(url, headId, token)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** The stream block inside a head config. All fields are optional on the wire. */
data class StreamAssignment(
    val streamUrl: String?,
    val streamUrlLan: String?,
    val streamAppId: String?,
    val streamMode: String?
) {
    companion object {
        fun fromJson(obj: JSONObject): StreamAssignment {
            return StreamAssignment(
                streamUrl = obj.optString("stream_url", "").ifEmpty { null },
                streamUrlLan = obj.optString("stream_url_lan", "").ifEmpty { null },
                streamAppId = obj.optString("stream_app_id", "").ifEmpty { null },
                streamMode = obj.optString("stream_mode", "").ifEmpty { null }
            )
        }
    }
}

/** Head configuration from GET /api/v1/heads/{head_id}. All fields optional. */
data class HeadConfig(
    val name: String?,
    val type: String?,
    val district: String?,
    val venue: String?,
    val stream: StreamAssignment?,
    val sunshineUsername: String?,
    val sunshinePassword: String?
) {
    /** An assignment is active when stream_url AND stream_app_id are non-empty. */
    val hasActiveAssignment: Boolean
        get() = !stream?.streamUrl.isNullOrEmpty() && !stream?.streamAppId.isNullOrEmpty()

    /**
     * Stream host: stream_url_lan when non-empty, else stream_url with the
     * scheme stripped. Null when no assignment is present.
     */
    val streamHost: String?
        get() {
            val s = stream ?: return null
            val lan = s.streamUrlLan
            if (!lan.isNullOrEmpty()) return stripScheme(lan)
            val url = s.streamUrl
            if (!url.isNullOrEmpty()) return stripScheme(url)
            return null
        }

    /** Sunshine credential fallback: sunshine/sunshine. */
    val effectiveSunshineUsername: String
        get() = sunshineUsername?.takeIf { it.isNotEmpty() } ?: "sunshine"

    val effectiveSunshinePassword: String
        get() = sunshinePassword?.takeIf { it.isNotEmpty() } ?: "sunshine"

    companion object {
        fun fromJson(json: String): HeadConfig {
            val obj = JSONObject(json)
            val streamObj = obj.optJSONObject("stream")
            return HeadConfig(
                name = obj.optString("name", "").ifEmpty { null },
                type = obj.optString("type", "").ifEmpty { null },
                district = obj.optString("district", "").ifEmpty { null },
                venue = obj.optString("venue", "").ifEmpty { null },
                stream = streamObj?.let { StreamAssignment.fromJson(it) },
                sunshineUsername = obj.optString("sunshine_username", "").ifEmpty { null },
                sunshinePassword = obj.optString("sunshine_password", "").ifEmpty { null }
            )
        }

        /** Remove scheme, path, and trailing slashes from a URL-ish string. */
        fun stripScheme(url: String): String {
            var s = url.trim()
            val schemeIdx = s.indexOf("://")
            if (schemeIdx >= 0) {
                s = s.substring(schemeIdx + 3)
            }
            s = s.trimEnd('/')
            val slashIdx = s.indexOf('/')
            if (slashIdx >= 0) {
                s = s.substring(0, slashIdx)
            }
            return s
        }
    }
}

/** One catalog entry from GET /api/v1/heads/{head_id}/experiences. */
data class Experience(
    val name: String,
    val label: String,
    val orientation: String?,
    val enableMicrophone: Boolean
) {
    val isPortrait: Boolean
        get() = orientation == "portrait"

    /** Portrait streams 1080x1920, everything else 1920x1080. */
    val streamWidth: Int
        get() = if (isPortrait) 1080 else 1920

    val streamHeight: Int
        get() = if (isPortrait) 1920 else 1080

    companion object {
        fun fromJson(obj: JSONObject): Experience {
            val name = obj.optString("name", "")
            return Experience(
                name = name,
                label = obj.optString("label", "").ifEmpty { name },
                orientation = obj.optString("orientation", "").ifEmpty { null },
                enableMicrophone = obj.optBoolean("enable_microphone", false)
            )
        }

        fun listFromJson(json: String): List<Experience> {
            val arr = JSONArray(json)
            val out = ArrayList<Experience>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val exp = fromJson(obj)
                if (exp.name.isNotEmpty()) {
                    out.add(exp)
                }
            }
            return out
        }
    }
}

/** One entry from GET /api/v1/bodies/eligible. All fields optional. */
data class EligibleBody(
    val id: String?,
    val name: String?,
    val ip: String?,
    val wireguardIp: String?,
    val sameVenue: Boolean,
    val streamCount: Int
) {
    /**
     * Candidate hosts in probe order:
     * 1. ip when it is an RFC1918 address (LAN first)
     * 2. wireguard_ip
     * 3. ip when not already listed
     */
    fun candidateHosts(): List<String> {
        val out = ArrayList<String>(3)
        val ipVal = ip?.takeIf { it.isNotEmpty() }
        val wgVal = wireguardIp?.takeIf { it.isNotEmpty() }
        if (ipVal != null && isRfc1918(ipVal)) {
            out.add(ipVal)
        }
        if (wgVal != null && !out.contains(wgVal)) {
            out.add(wgVal)
        }
        if (ipVal != null && !out.contains(ipVal)) {
            out.add(ipVal)
        }
        return out
    }

    companion object {
        /** True for 10.*, 192.168.*, and 172.16.* through 172.31.* addresses. */
        fun isRfc1918(host: String): Boolean {
            if (host.startsWith("10.")) return true
            if (host.startsWith("192.168.")) return true
            if (host.startsWith("172.")) {
                val second = host.split(".").getOrNull(1)?.toIntOrNull() ?: return false
                return second in 16..31
            }
            return false
        }

        fun fromJson(obj: JSONObject): EligibleBody {
            return EligibleBody(
                id = obj.optString("id", "").ifEmpty { null },
                name = obj.optString("name", "").ifEmpty { null },
                ip = obj.optString("ip", "").ifEmpty { null },
                wireguardIp = obj.optString("wireguard_ip", "").ifEmpty { null },
                sameVenue = obj.optBoolean("same_venue", false),
                // A missing stream_count counts as 0 (free body).
                streamCount = obj.optInt("stream_count", 0)
            )
        }

        fun listFromJson(json: String): List<EligibleBody> {
            val arr = JSONArray(json)
            val out = ArrayList<EligibleBody>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                out.add(fromJson(obj))
            }
            return out
        }
    }
}

/** Result of GET /api/v1/heads/{head_id}/commands. */
data class HeadCommand(
    val screenshot: Boolean
) {
    companion object {
        fun fromJson(json: String): HeadCommand {
            val obj = JSONObject(json)
            return HeadCommand(screenshot = obj.optBoolean("screenshot", false))
        }
    }
}

/** Heartbeat diagnostics block. The app field is always "kiosk". */
data class HeadDiagnostics(
    val version: String,
    val wireguard: String,
    val routing: String,
    val latencyMs: String,
    val wifiSsid: String,
    val localIp: String,
    val moonlightClientId: String?
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("version", version)
        obj.put("wireguard", wireguard)
        obj.put("app", "kiosk")
        obj.put("routing", routing)
        obj.put("latency_ms", latencyMs)
        obj.put("wifi_ssid", wifiSsid)
        obj.put("local_ip", localIp)
        obj.put("moonlight_client_id", moonlightClientId ?: JSONObject.NULL)
        return obj
    }
}
