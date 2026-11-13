/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.hub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.CallLog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.interop.MetadataSidecar
import com.baba.callvault.utils.AppLogger
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Settings shared by the hub workers: the hub is used when the device key is a hub token. */
internal object Hub {
    fun client(context: Context): HubClient? {
        val prefs = AppPreferences(context)
        val url = prefs.getAvanegarServerUrl()
        val key = prefs.getAvanegarDeviceKey()
        if (!prefs.isAvanegarUploadEnabled() || url.isEmpty() || !HubClient.isHubToken(key)) return null
        return HubClient(url, key)
    }

    fun isOn(context: Context): Boolean {
        val prefs = AppPreferences(context)
        return prefs.isAvanegarUploadEnabled() && HubClient.isHubToken(prefs.getAvanegarDeviceKey())
    }

    private val iso = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US) }
    fun isoTime(ms: Long): String = iso.get()!!.format(Date(ms))

    /** Last 10 digits, enough to match +98912... with 0912... */
    fun phoneKey(phone: String?): String = phone.orEmpty().filter { it.isDigit() }.takeLast(10)
}

/**
 * Sends the phone's whole call log to the hub, recorded or not, missed calls included: the hub is the
 * single record of every company call. Incremental by CallLog _ID; re-sending is harmless.
 */
class HubCallLogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val client = Hub.client(applicationContext) ?: return Result.success()
        return try {
            syncCallLog(applicationContext, client)
            Result.success()
        } catch (e: IOException) {
            AppLogger.w(TAG, "Call log sync failed, will retry: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CV:HubCallLog"
        private const val STATE = "hub_sync"
        private const val LAST_ID = "last_call_log_id"
        private const val BATCH = 200
        /** Only the last days on first run, so a new install does not upload years of history. */
        private const val FIRST_RUN_DAYS = 7L

        fun schedule(context: Context) {
            if (!Hub.isOn(context)) return
            val request = PeriodicWorkRequestBuilder<HubCallLogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("hub_call_log", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun syncNow(context: Context) {
            if (!Hub.isOn(context)) return
            val request = OneTimeWorkRequestBuilder<HubCallLogWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("hub_call_log_now", ExistingWorkPolicy.REPLACE, request)
        }

        /** Reads new call-log rows and posts them in batches. Throws [IOException] when the hub is unreachable. */
        internal fun syncCallLog(context: Context, client: HubClient) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED
            ) {
                AppLogger.w(TAG, "No call log permission; only recorded calls reach the hub")
                return
            }
            val state = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
            while (true) {
                val last = state.getLong(LAST_ID, -1L)
                val rows = readRows(context, last)
                if (rows.isEmpty()) return
                client.syncCalls(rows.map { it.second })
                state.edit { putLong(LAST_ID, rows.last().first) }
                AppLogger.i(TAG, "Sent ${rows.size} call-log rows to the hub")
                if (rows.size < BATCH) return
            }
        }

        private fun readRows(context: Context, afterId: Long): List<Pair<Long, HubClient.CallRow>> {
            val since = if (afterId < 0) System.currentTimeMillis() - TimeUnit.DAYS.toMillis(FIRST_RUN_DAYS) else 0L
            val out = mutableListOf<Pair<Long, HubClient.CallRow>>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                    CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.DATE} >= ?",
                arrayOf(afterId.toString(), since.toString()),
                "${CallLog.Calls._ID} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < BATCH) {
                    val id = c.getLong(0)
                    val number = c.getString(1).orEmpty()
                    if (number.isBlank()) continue
                    out += id to rowFor(id, number, c.getString(2), c.getInt(3), c.getLong(4), c.getInt(5))
                }
            }
            return out
        }

        internal fun rowFor(id: Long, number: String, name: String?, type: Int, date: Long, duration: Int): HubClient.CallRow {
            val (direction, outcome) = when (type) {
                CallLog.Calls.OUTGOING_TYPE -> "out" to (if (duration > 0) "answered" else "no_answer")
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.VOICEMAIL_TYPE -> "in" to "missed"
                CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> "in" to "rejected"
                else -> "in" to (if (duration > 0) "answered" else "missed")
            }
            return HubClient.CallRow("log-$id", direction, outcome, number, name?.ifBlank { null }, null,
                Hub.isoTime(date), 0, duration)
        }

        /** The call-log row of a recorded call: same number, started within two minutes. */
        internal fun findLogRow(context: Context, phone: String?, startMs: Long): HubClient.CallRow? {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED || phone.isNullOrBlank()
            ) return null
            val key = Hub.phoneKey(phone)
            val window = TimeUnit.MINUTES.toMillis(2)
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                    CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} BETWEEN ? AND ?",
                arrayOf((startMs - window).toString(), (startMs + window).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    if (Hub.phoneKey(c.getString(1)) == key) {
                        return rowFor(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4), c.getInt(5))
                    }
                }
            }
            return null
        }
    }
}

/**
 * Sends one finished recording to the hub: first the call (matched to its call-log row so it is not
 * counted twice), then the audio with its SHA-256. Retried with backoff; nothing is lost.
 */
class HubUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val uri = inputData.getString(KEY_URI)?.toUri() ?: return Result.failure()
        val mime = when (name.substringAfterLast('.', "").lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> inputData.getString(KEY_MIME) ?: "application/octet-stream"
        }
        val client = Hub.client(applicationContext) ?: return Result.success()
        if (runAttemptCount >= MAX_ATTEMPTS) {
            AppLogger.e(TAG, "Giving up on '$name' after $runAttemptCount attempts; it stays on the phone")
            return Result.failure()
        }
        val prefs = AppPreferences(applicationContext)
        val details = readDetails(prefs, name)
        if (details == null && prefs.isWriteMetadataFileEnabled() && runAttemptCount < 3) return Result.retry()

        val startMs = details?.optLong("timestamp_unix_ms", 0L)?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val call0 = details?.optJSONArray("calls")?.optJSONObject(0)
        val phone = call0?.optString("phone_number")?.takeIf { it.isNotBlank() && it != "null" }
        val contact = call0?.optString("contact_name")?.takeIf { it.isNotBlank() && it != "null" }
        val duration = details?.optJSONObject("output")?.optJSONObject("recording")
            ?.optDouble("duration_secs_encoded")?.takeIf { !it.isNaN() }?.toInt() ?: 0
        val direction = when (details?.optString("direction")) { "out" -> "out"; else -> "in" }

        val row = HubCallLogWorker.findLogRow(applicationContext, phone, startMs)
            ?: HubClient.CallRow("rec-$name", direction, "answered", phone ?: "unknown", contact, null,
                Hub.isoTime(startMs), 0, duration)

        return try {
            val callId = client.syncCalls(listOf(row))[row.deviceCallId] ?: return Result.retry()
            val (sha, size) = digest(uri) ?: return Result.failure()
            val channels = channelCount(uri)
            val result = applicationContext.contentResolver.openInputStream(uri)?.use {
                client.uploadRecording(callId, name, mime, channels, sha, size, it)
            } ?: return Result.failure()
            when (result) {
                HubClient.UploadResult.Stored -> {
                    AppLogger.i(TAG, "Sent '$name' to the hub (call $callId, channels=$channels)")
                    applicationContext.getSharedPreferences(IDS, Context.MODE_PRIVATE).edit { putString(name, callId) }
                    Result.success()
                }
                is HubClient.UploadResult.Retry -> { AppLogger.w(TAG, "'$name': ${result.reason}"); Result.retry() }
                is HubClient.UploadResult.Rejected -> { AppLogger.e(TAG, "'$name' refused: ${result.reason}"); Result.failure() }
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Hub unreachable for '$name': ${e.message}")
            Result.retry()
        }
    }

    private fun readDetails(prefs: AppPreferences, name: String): JSONObject? = runCatching {
        val folder = prefs.getRecordingFolderUri() ?: return null
        val file = DocumentFile.fromTreeUri(applicationContext, folder)
            ?.findFile(MetadataSidecar.sidecarNameFor(name)) ?: return null
        applicationContext.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { JSONObject(it.readText()) }
    }.getOrNull()

    private fun digest(uri: Uri): Pair<String, Long>? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        applicationContext.contentResolver.openInputStream(uri)!!.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                size += n
            }
        }
        md.digest().joinToString("") { "%02x".format(it) } to size
    }.getOrNull()

    private fun channelCount(uri: Uri): Int = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(applicationContext, uri, null)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
        } finally {
            extractor.release()
        }
    }.getOrDefault(1).coerceIn(1, 2)

    companion object {
        private const val TAG = "CV:HubUpload"
        const val KEY_NAME = "name"
        const val KEY_URI = "uri"
        const val KEY_MIME = "mime"
        private const val MAX_ATTEMPTS = 30
        private const val IDS = "hub_call_ids"

        fun enqueue(context: Context, name: String, uri: Uri, mimeType: String) {
            val prefs = AppPreferences(context)
            val network = if (prefs.isAvanegarWifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<HubUploadWorker>()
                .setInputData(workDataOf(KEY_NAME to name, KEY_URI to uri.toString(), KEY_MIME to mimeType))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("hub_upload:$name", ExistingWorkPolicy.KEEP, request)
            HubCallLogWorker.syncNow(context)
        }

        /** The hub's call id for a recording sent from this phone, for opening its summary. */
        fun callIdFor(context: Context, name: String): String? =
            context.getSharedPreferences(IDS, Context.MODE_PRIVATE).getString(name, null)
    }
}
