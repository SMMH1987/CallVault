/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.hub

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for the Hamdabir hub server (our own server, not the Avanegar API). The phone holds only a
 * per-device token ("hdv_..."); the hub holds the Avanegar key and feeds Bitrix24, ERP and Golnameh.
 */
internal class HubClient(baseUrl: String, private val token: String) {

    private val base = baseUrl.trim().trimEnd('/')

    /** One row of the phone's call log, as the hub's POST /v1/calls expects it. */
    data class CallRow(
        val deviceCallId: String,
        val direction: String,      // "in" | "out"
        val outcome: String,        // answered | missed | rejected | busy | no_answer | failed
        val phone: String,
        val contactName: String?,
        val simSlot: Int?,
        val startedAtIso: String,   // with offset
        val ringSeconds: Int,
        val talkSeconds: Int,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("device_call_id", deviceCallId)
            put("direction", direction)
            put("outcome", outcome)
            put("phone", phone)
            if (contactName != null) put("contact_name", contactName)
            if (simSlot != null) put("sim_slot", simSlot)
            put("started_at", startedAtIso)
            put("ring_seconds", ringSeconds)
            put("talk_seconds", talkSeconds)
        }
    }

    /** POST /v1/calls. Returns device_call_id -> hub call id. Throws [IOException] on failure. */
    fun syncCalls(rows: List<CallRow>): Map<String, String> {
        val body = JSONObject().put("calls", JSONArray().apply { rows.forEach { put(it.toJson()) } })
        val json = JSONObject(request("POST", "/v1/calls", body.toString().toByteArray(Charsets.UTF_8), "application/json"))
        val out = mutableMapOf<String, String>()
        val calls = json.getJSONArray("calls")
        for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            out[c.getString("device_call_id")] = c.getString("id")
        }
        return out
    }

    sealed interface UploadResult {
        data object Stored : UploadResult
        data class Retry(val reason: String) : UploadResult
        data class Rejected(val reason: String) : UploadResult
    }

    /** PUT /v1/calls/{id}/recording with the raw audio. The hub refuses it if [sha256] does not match. */
    fun uploadRecording(callId: String, fileName: String, mime: String, channels: Int, sha256: String,
                        size: Long, audio: InputStream): UploadResult {
        val q = "filename=" + URLEncoder.encode(asciiName(fileName), "UTF-8") + "&channels=" + channels + "&sha256=" + sha256
        val conn = try {
            open("/v1/calls/$callId/recording?$q", "PUT").apply {
                doOutput = true
                setRequestProperty("Content-Type", mime)
                if (size > 0) setFixedLengthStreamingMode(size) else setChunkedStreamingMode(CHUNK)
            }
        } catch (e: IOException) {
            return UploadResult.Retry("could not connect: ${e.message}")
        }
        return try {
            conn.outputStream.use { audio.copyTo(it, CHUNK) }
            val code = conn.responseCode
            val text = readBody(conn)
            when {
                code in 200..299 -> UploadResult.Stored
                code == 401 || code == 404 || code == 409 || code == 413 -> UploadResult.Rejected("HTTP $code $text")
                else -> UploadResult.Retry("HTTP $code $text")   // 422 sha mismatch: upload again
            }
        } catch (e: IOException) {
            UploadResult.Retry("upload interrupted: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /** GET /v1/calls/{id}: transcript turns, structured summary, recording state. */
    fun callDetail(callId: String): JSONObject = JSONObject(request("GET", "/v1/calls/$callId"))

    /** GET /v1/customers/{phone}/calls: every company call with this number. */
    fun customerCalls(phone: String): JSONArray =
        JSONObject(request("GET", "/v1/customers/" + URLEncoder.encode(phone, "UTF-8") + "/calls")).getJSONArray("calls")

    private fun request(method: String, path: String, body: ByteArray? = null, type: String? = null): String {
        val conn = open(path, method)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val text = readBody(conn)
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }

    private fun readBody(conn: HttpURLConnection): String = runCatching {
        (if (conn.responseCode >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() }
    }.getOrNull().orEmpty()

    private fun asciiName(name: String) = name.map { if (it.code in 0x21..0x7e) it else '_' }.joinToString("")

    companion object {
        private const val CHUNK = 64 * 1024

        /** A hub device token, as opposed to an Avanegar device key. */
        fun isHubToken(key: String) = key.startsWith("hdv_")
    }
}
