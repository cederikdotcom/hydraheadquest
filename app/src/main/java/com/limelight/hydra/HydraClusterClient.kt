package com.limelight.hydra

import com.limelight.hydra.model.EligibleBody
import com.limelight.hydra.model.EnrollmentConfig
import com.limelight.hydra.model.Experience
import com.limelight.hydra.model.HeadCommand
import com.limelight.hydra.model.HeadConfig
import com.limelight.hydra.model.HeadDiagnostics
import com.limelight.hydra.model.XrSessionState
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * HTTP client for the hydracluster head API.
 *
 * Uses HttpURLConnection so no new dependency is needed. hydracluster serves
 * Let's Encrypt certificates, so default TLS validation stays in place.
 * All calls are blocking; run them off the main thread.
 *
 * See docs/hydra-api-contract.md for the wire contract.
 */
class HydraClusterClient(private val config: EnrollmentConfig) {

    /**
     * IOException carrying the HTTP status code of a non-2xx response,
     * so callers can branch on specific codes (404 = no XR session, 409
     * = body busy) without parsing the message. Every non-2xx thrown by
     * this client is of this type; plain IOExceptions remain transport
     * failures.
     */
    class HttpStatusException(val code: Int, message: String) : IOException(message)

    companion object {
        /** Request timeout for API calls, in milliseconds. */
        const val REQUEST_TIMEOUT_MS = 15000

        /** Timeout for larger resource transfers (screenshot upload). */
        const val RESOURCE_TIMEOUT_MS = 30000

        /**
         * Enroll this device in the fleet.
         *
         * POST /api/v1/heads with the fleet enrollment token as bearer auth.
         * The response carries the per-device token used for every later call.
         */
        @Throws(IOException::class)
        fun enroll(serverUrl: String, enrollmentToken: String, name: String): EnrollmentConfig {
            val base = serverUrl.trimEnd('/')
            // "type" makes the cluster enroll this head with the
            // hydraheadquest role. Every XR gate (XR tiles in the catalog,
            // the xr-session endpoints, XR assignments) checks that role.
            // An old cluster ignores the unknown field and enrolls the
            // head with today's iPad behavior, which keeps the flat path
            // working there.
            val body = JSONObject()
                .put("name", name)
                .put("type", "hydraheadquest")
                .toString()
            val response = rawRequest(
                url = "$base/api/v1/heads",
                method = "POST",
                bearerToken = enrollmentToken,
                body = body.toByteArray(StandardCharsets.UTF_8),
                contentType = "application/json",
                timeoutMs = REQUEST_TIMEOUT_MS
            )
            val text = String(response, StandardCharsets.UTF_8)
            return EnrollmentConfig.fromJson(text)
                ?: throw IOException("Enrollment response missing fields: $text")
        }

        @Throws(IOException::class)
        private fun rawRequest(
            url: String,
            method: String,
            bearerToken: String,
            body: ByteArray?,
            contentType: String?,
            timeoutMs: Int,
            accept: String = "application/json"
        ): ByteArray {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Authorization", "Bearer $bearerToken")
                conn.setRequestProperty("Accept", accept)
                if (body != null) {
                    if (contentType != null) {
                        conn.setRequestProperty("Content-Type", contentType)
                    }
                    // Android's HttpURLConnection is OkHttp backed and accepts
                    // bodies on DELETE, which the stream stop call needs.
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                val stream: InputStream? = if (code in 200..299) {
                    conn.inputStream
                } else {
                    conn.errorStream
                }
                val bytes = stream?.use { readAll(it) } ?: ByteArray(0)
                if (code !in 200..299) {
                    val text = String(bytes, StandardCharsets.UTF_8).take(512)
                    throw HttpStatusException(code, "HTTP $code from $method $url: $text")
                }
                return bytes
            } finally {
                conn.disconnect()
            }
        }

        private fun readAll(stream: InputStream): ByteArray {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
            }
            return buffer.toByteArray()
        }
    }

    private val headPath: String
        get() = "/api/v1/heads/${config.headId}"

    /** GET /api/v1/heads/{head_id} */
    @Throws(IOException::class)
    fun getHeadConfig(): HeadConfig {
        val response = request("GET", headPath, null, null, REQUEST_TIMEOUT_MS)
        return HeadConfig.fromJson(String(response, StandardCharsets.UTF_8))
    }

    /**
     * PUT /api/v1/heads/{head_id}
     *
     * Heartbeat: every 30 s when idle, every 5 s while streaming.
     * Status is one of idle, self-service, starting, streaming, error.
     */
    @Throws(IOException::class)
    fun putHeartbeat(status: String, bodyId: String?, diagnostics: HeadDiagnostics) {
        val body = JSONObject()
        body.put("status", status)
        body.put("body_id", bodyId ?: JSONObject.NULL)
        body.put("diagnostics", diagnostics.toJson())
        request(
            "PUT", headPath,
            body.toString().toByteArray(StandardCharsets.UTF_8),
            "application/json", REQUEST_TIMEOUT_MS
        )
    }

    /** GET /api/v1/heads/{head_id}/experiences */
    @Throws(IOException::class)
    fun getExperiences(): List<Experience> {
        val response = request("GET", "$headPath/experiences", null, null, REQUEST_TIMEOUT_MS)
        return Experience.listFromJson(String(response, StandardCharsets.UTF_8))
    }

    /**
     * GET /api/v1/bodies/eligible?district=&venue=&head_id=&experience=
     *
     * Pass streamMode "xr" for XR body discovery: a new cluster filters
     * to XR-capable idle bodies and adds xr_drivers / xr_state to each
     * entry; an old cluster ignores the parameter, which is why callers
     * must re-filter on xr_drivers client-side. Null keeps today's flat
     * query byte-identical.
     */
    @Throws(IOException::class)
    fun getEligibleBodies(
        district: String?,
        venue: String?,
        headId: String,
        experience: String,
        streamMode: String? = null
    ): List<EligibleBody> {
        val query = StringBuilder("/api/v1/bodies/eligible?")
        query.append("district=").append(encode(district ?: ""))
        query.append("&venue=").append(encode(venue ?: ""))
        query.append("&head_id=").append(encode(headId))
        query.append("&experience=").append(encode(experience))
        if (streamMode != null) {
            query.append("&stream_mode=").append(encode(streamMode))
        }
        val response = request("GET", query.toString(), null, null, REQUEST_TIMEOUT_MS)
        return EligibleBody.listFromJson(String(response, StandardCharsets.UTF_8))
    }

    /**
     * GET /api/v1/heads/{head_id}/wireguard-config
     *
     * Returns the ready-made wg-quick config as plain text. The body carries
     * this head's PRIVATE KEY: never log it, store it app-private only.
     * Throws on any non-200; a 404 means no tunnel is provisioned for this
     * head. An empty body is treated as not provisioned too (contract
     * section 12). Fetch once, reuse across launches, refetch only on
     * explicit operator action.
     */
    @Throws(IOException::class)
    fun getWireguardConfig(): String {
        val response = rawRequest(
            url = config.baseUrl + "$headPath/wireguard-config",
            method = "GET",
            bearerToken = config.token,
            body = null,
            contentType = null,
            timeoutMs = REQUEST_TIMEOUT_MS,
            accept = "text/plain"
        )
        val text = String(response, StandardCharsets.UTF_8)
        if (text.isBlank()) {
            throw IOException("wireguard-config: empty body (not provisioned)")
        }
        return text
    }

    /** GET /api/v1/heads/{head_id}/commands, polled every 3 s. */
    @Throws(IOException::class)
    fun getCommands(): HeadCommand {
        val response = request("GET", "$headPath/commands", null, null, REQUEST_TIMEOUT_MS)
        return HeadCommand.fromJson(String(response, StandardCharsets.UTF_8))
    }

    /**
     * POST /api/v1/heads/{head_id}/screenshot
     *
     * Raw JPEG bytes with Content-Type image/jpeg. No multipart.
     */
    @Throws(IOException::class)
    fun postScreenshot(jpegBytes: ByteArray) {
        request("POST", "$headPath/screenshot", jpegBytes, "image/jpeg", RESOURCE_TIMEOUT_MS)
    }

    /**
     * DELETE /api/v1/heads/{head_id}/stream with body {"body_id": ...}.
     *
     * Await this on a user stop before showing the grid again.
     * Fire and forget on a stream interrupt.
     */
    @Throws(IOException::class)
    fun deleteStream(bodyId: String) {
        val body = JSONObject().put("body_id", bodyId).toString()
        request(
            "DELETE", "$headPath/stream",
            body.toByteArray(StandardCharsets.UTF_8),
            "application/json", REQUEST_TIMEOUT_MS
        )
    }

    // ------------------------------------------------------------------
    // XR session (immersive ALVR path, issue #558)
    // ------------------------------------------------------------------

    /**
     * POST /api/v1/heads/{head_id}/xr-session
     *
     * Ask the cluster to arm the given body for this head. Returns the
     * created session (state "requested") on 201. Failure surfaces as
     * [HttpStatusException]: 409 body_busy / not_xr_capable, and 404 on
     * a cluster without XR support.
     */
    @Throws(IOException::class)
    fun postXrSession(
        bodyId: String,
        experience: String,
        clientHostname: String,
        clientIp: String?
    ): XrSessionState {
        val body = JSONObject()
        body.put("body_id", bodyId)
        body.put("experience", experience)
        body.put("client_hostname", clientHostname)
        body.put("client_ip", clientIp ?: JSONObject.NULL)
        val response = request(
            "POST", "$headPath/xr-session",
            body.toString().toByteArray(StandardCharsets.UTF_8),
            "application/json", REQUEST_TIMEOUT_MS
        )
        return XrSessionState.fromJson(String(response, StandardCharsets.UTF_8))
    }

    /**
     * GET /api/v1/heads/{head_id}/xr-session
     *
     * The current XR session of this head, or null when the head has no
     * session (404). Polled every 5 s while arming or in an XR session;
     * null IS the end signal there.
     */
    @Throws(IOException::class)
    fun getXrSession(): XrSessionState? {
        return try {
            val response = request("GET", "$headPath/xr-session", null, null, REQUEST_TIMEOUT_MS)
            XrSessionState.fromJson(String(response, StandardCharsets.UTF_8))
        } catch (e: HttpStatusException) {
            if (e.code == 404) null else throw e
        }
    }

    /**
     * DELETE /api/v1/heads/{head_id}/xr-session
     *
     * End this head's XR session: the cluster marks it "ending" and the
     * body tears the chain down. Idempotent; a 404 (already gone) is
     * treated as success.
     */
    @Throws(IOException::class)
    fun deleteXrSession() {
        try {
            request("DELETE", "$headPath/xr-session", null, null, REQUEST_TIMEOUT_MS)
        } catch (e: HttpStatusException) {
            if (e.code != 404) throw e
        }
    }

    @Throws(IOException::class)
    private fun request(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String?,
        timeoutMs: Int
    ): ByteArray {
        return rawRequest(config.baseUrl + path, method, config.token, body, contentType, timeoutMs)
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }
}
