package com.limelight.hydra

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Issue reporting to the Hydra tracker, matching the iPad DiagnosticsView:
 * a form-urlencoded POST to https://issues.experiencenet.com/report with
 * title, description, project, reporter, head, district, and venue fields.
 * Project is hydraheadquest, reporter is quest-head.
 */
object HydraIssueReporter {

    private const val REPORT_URL = "https://issues.experiencenet.com/report"
    private const val PROJECT = "hydraheadquest"
    private const val REPORTER = "quest-head"
    private const val TIMEOUT_MS = 15000

    /**
     * File an issue. Blocking; call from a background thread.
     * Throws IOException with "HTTP code: body" on a non-2xx response.
     */
    @Throws(IOException::class)
    fun report(
        title: String,
        description: String,
        headId: String?,
        district: String?,
        venue: String?
    ) {
        val form = StringBuilder()
        appendField(form, "title", title)
        appendField(form, "description", description)
        appendField(form, "project", PROJECT)
        appendField(form, "reporter", REPORTER)
        appendField(form, "head", headId ?: "")
        appendField(form, "district", district ?: "")
        appendField(form, "venue", venue ?: "")

        val conn = URL(REPORT_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.outputStream.use {
                it.write(form.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.use { s ->
                    String(s.readBytes(), StandardCharsets.UTF_8).take(256)
                } ?: ""
                throw IOException("HTTP $code: $body")
            }
            // Drain the success body so the connection can be reused.
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun appendField(form: StringBuilder, name: String, value: String) {
        if (form.isNotEmpty()) {
            form.append('&')
        }
        form.append(name).append('=').append(URLEncoder.encode(value, "UTF-8"))
    }
}
