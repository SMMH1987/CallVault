/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.avanegar

import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject

/**
 * The few Avanegar API calls this app makes, over plain [HttpURLConnection] (no extra dependency).
 *
 * The app authenticates with a **device key**: a sub-key the account owner creates per phone, limited
 * to submitting recordings and reading its own results up to a monthly cap. The account's main key is
 * never put on a phone; a lost phone is handled by revoking its key alone.
 */
internal class AvanegarClient(baseUrl: String, private val deviceKey: String) {

    private val base = baseUrl.trim().trimEnd('/')

    /** What a submit came back with. */
    sealed interface SubmitResult {
        /** Accepted (or already accepted earlier under the same idempotency key). */
        data class Accepted(val jobId: String) : SubmitResult
        /** Worth trying again later: network trouble, a 5xx, a rate limit, or an empty wallet. */
        data class Retry(val reason: String) : SubmitResult
        /** Will not succeed by retrying: a bad key, a refused file, a wrong URL. */
        data class Rejected(val reason: String) : SubmitResult
    }

    /**
     * Uploads one recording for transcription.
     *
     * @param split true for a dual-track file (L = this phone's user, R = the other party), so the server
     *              transcribes each side on its own and labels every turn near/far.
     */
    fun submit(
        fileName: String,
        mimeType: String,
        audio: InputStream,
        idempotencyKey: String,
        externalId: String,
        metadata: JSONObject,
        split: Boolean,
    ): SubmitResult {
        val boundary = "----cv" + UUID.randomUUID().toString().replace("-", "")
        val conn = try {
            open("/v1/transcriptions", "POST").apply {
                setRequestProperty("Idempotency-Key", idempotencyKey)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                doOutput = true
                setChunkedStreamingMode(CHUNK_BYTES)
            }
        } catch (e: IOException) {
            return SubmitResult.Retry("could not connect: ${e.message}")
        }
        return try {
            DataOutputStream(conn.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n")
                    out.writeBytes("Content-Type: text/plain; charset=utf-8\r\n\r\n")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
                field("external_id", externalId)
                field("metadata", metadata.toString())
                if (split) field("channels", "split")
                out.writeBytes("--$boundary\r\n")
                // The name goes through as ASCII-safe: a contact name in Persian would otherwise have to be
                // RFC 5987-encoded, and the server keys on external_id, not on this.
                val safeName = fileName.map { if (it.code in 0x20..0x7e && it != '"') it else '_' }.joinToString("")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n")
                out.writeBytes("Content-Type: $mimeType\r\n\r\n")
                audio.copyTo(out, CHUNK_BYTES)
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            val code = conn.responseCode
            val body = readBody(conn)
            when {
                code in 200..299 -> {
                    val id = runCatching { JSONObject(body).opt("id")?.toString() }.getOrNull().orEmpty()
                    SubmitResult.Accepted(id)
                }
                // 409 = the same idempotency key was already used: the first upload got through.
                code == 409 -> SubmitResult.Accepted(runCatching { JSONObject(body).opt("id")?.toString() }.getOrNull().orEmpty())
                // 402 = wallet empty or the device's monthly cap reached; 429 = rate limit. Both clear up.
                code == 402 || code == 429 || code >= 500 -> SubmitResult.Retry("HTTP $code ${problemTitle(body)}")
                else -> SubmitResult.Rejected("HTTP $code ${problemTitle(body)}")
            }
        } catch (e: IOException) {
            SubmitResult.Retry("upload interrupted: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /** The wallet as this key sees it, for the settings screen. */
    data class Usage(val balanceCredits: Long?, val balanceMinutes: Double?)

    /** GET /v1/usage. Throws [IOException] with a readable message when it fails. */
    fun usage(): Usage {
        val conn = open("/v1/usage", "GET")
        try {
            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) throw IOException("HTTP $code ${problemTitle(body)}")
            val json = JSONObject(body)
            return Usage(
                balanceCredits = if (json.has("balance_credits")) json.optLong("balance_credits") else null,
                balanceMinutes = if (json.has("balance_minutes")) json.optDouble("balance_minutes") else null,
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $deviceKey")
            setRequestProperty("Accept", "application/json")
        }

    private fun readBody(conn: HttpURLConnection): String =
        runCatching {
            (if (conn.responseCode >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()

    /** The `title` of an RFC 7807 problem body, which the API uses for every error. */
    private fun problemTitle(body: String): String =
        runCatching { JSONObject(body).optString("title") }.getOrNull().orEmpty()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val CHUNK_BYTES = 64 * 1024
    }
}
