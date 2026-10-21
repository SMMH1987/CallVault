/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.avanegar

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baba.callvault.BuildConfig
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.interop.MetadataSidecar
import com.baba.callvault.utils.AppLogger
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Sends one finished recording, with its details file, to the Avanegar server for transcription.
 *
 * Retried with backoff for anything that can clear up on its own — no network, a server error, an empty
 * wallet or a device cap reached — so a recording waits on the phone rather than being lost. The same
 * idempotency key on every attempt means a retry after an upload that did get through is not charged
 * twice.
 */
class AvanegarUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val uri = inputData.getString(KEY_URI)?.toUri() ?: return Result.failure()
        // The container's type, not the codec's ("audio/mp4a-latm" is AAC inside the .m4a, which a server
        // reading the upload's Content-Type would not recognise as a file).
        val mime = when (name.substringAfterLast('.', "").lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            else -> inputData.getString(KEY_MIME) ?: "application/octet-stream"
        }
        val prefs = AppPreferences(applicationContext)

        val baseUrl = prefs.getAvanegarServerUrl()
        val key = prefs.getAvanegarDeviceKey()
        if (!prefs.isAvanegarUploadEnabled() || baseUrl.isEmpty() || key.isEmpty()) {
            AppLogger.i(TAG, "Not sending '$name': server upload is off or not set up")
            return Result.success()
        }
        if (runAttemptCount >= MAX_ATTEMPTS) {
            AppLogger.e(TAG, "Giving up on sending '$name' after $runAttemptCount attempts; it stays on the phone")
            return Result.failure()
        }

        // The details file is written just after the audio. Give it a moment rather than send without it.
        val details = readDetails(prefs, name)
        if (details == null && prefs.isWriteMetadataFileEnabled() && runAttemptCount < DETAILS_WAIT_ATTEMPTS) {
            AppLogger.i(TAG, "Details file for '$name' not there yet; trying again shortly")
            return Result.retry()
        }

        val split = channelCount(uri) == 2
        val metadata = JSONObject().apply {
            put("file_name", name)
            put("device_name", prefs.getAvanegarDeviceName())
            put("sim_numbers", prefs.getAvanegarSimNumbers())
            put("app_version", BuildConfig.VERSION_NAME)
            // L = this phone's user, R = the other party, whenever the file is dual-track.
            if (split) put("channel_map", JSONObject().put("left", "near").put("right", "far"))
            if (details != null) put("details", details)
        }

        val stream = runCatching { applicationContext.contentResolver.openInputStream(uri) }.getOrNull()
        if (stream == null) {
            AppLogger.e(TAG, "Not sending '$name': the recording can no longer be opened")
            return Result.failure()
        }
        val result = stream.use {
            AvanegarClient(baseUrl, key).submit(
                fileName = name,
                mimeType = mime,
                audio = it,
                idempotencyKey = sha256("$key|$name"),
                externalId = name,
                metadata = metadata,
                split = split,
            )
        }
        return when (result) {
            is AvanegarClient.SubmitResult.Accepted -> {
                AppLogger.i(TAG, "Sent '$name' to Avanegar (job ${result.jobId.ifEmpty { "?" }}, split=$split)")
                rememberJob(applicationContext, name, result.jobId)
                Result.success()
            }
            is AvanegarClient.SubmitResult.Retry -> {
                AppLogger.w(TAG, "Could not send '$name' yet (${result.reason}); will retry")
                Result.retry()
            }
            is AvanegarClient.SubmitResult.Rejected -> {
                AppLogger.e(TAG, "Server refused '$name': ${result.reason}")
                Result.failure()
            }
        }
    }

    /** The details file beside the recording, as JSON, or null when there is none (yet). */
    private fun readDetails(prefs: AppPreferences, name: String): JSONObject? = runCatching {
        val folder = prefs.getRecordingFolderUri() ?: return null
        val file = DocumentFile.fromTreeUri(applicationContext, folder)
            ?.findFile(MetadataSidecar.sidecarNameFor(name)) ?: return null
        applicationContext.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { JSONObject(it.readText()) }
    }.getOrNull()

    private fun channelCount(uri: Uri): Int = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(applicationContext, uri, null)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
        } finally {
            extractor.release()
        }
    }.getOrDefault(1)

    companion object {
        private const val TAG = "CV:AvanegarUpload"
        const val KEY_NAME = "name"
        const val KEY_URI = "uri"
        const val KEY_MIME = "mime"
        private const val MAX_ATTEMPTS = 30
        private const val DETAILS_WAIT_ATTEMPTS = 3
        private const val JOBS_PREFS = "avanegar_jobs"

        /**
         * Queues [name] for sending, if server upload is on. One unique job per recording, so a second
         * call for the same file (a retried finalise, a VoIP and carrier overlap) does not upload twice.
         */
        fun enqueue(context: Context, name: String, uri: Uri, mimeType: String) {
            val prefs = AppPreferences(context)
            if (!prefs.isAvanegarUploadEnabled()) return
            val network = if (prefs.isAvanegarWifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<AvanegarUploadWorker>()
                .setInputData(workDataOf(KEY_NAME to name, KEY_URI to uri.toString(), KEY_MIME to mimeType))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("avanegar_upload:$name", ExistingWorkPolicy.KEEP, request)
            AppLogger.i(TAG, "Queued '$name' for Avanegar (wifiOnly=${prefs.isAvanegarWifiOnly()})")
        }

        /** The server's job id for a recording sent from this phone, or null if it has not been sent. */
        fun jobIdFor(context: Context, name: String): String? =
            context.getSharedPreferences(JOBS_PREFS, Context.MODE_PRIVATE).getString(name, null)

        private fun rememberJob(context: Context, name: String, jobId: String) =
            context.getSharedPreferences(JOBS_PREFS, Context.MODE_PRIVATE).edit { putString(name, jobId) }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
