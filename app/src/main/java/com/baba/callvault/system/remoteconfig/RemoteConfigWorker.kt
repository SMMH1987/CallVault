/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.remoteconfig

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.updates.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Fetches the app config and acts on it: a newer version raises the Home banner and the "update
 * available" notification, exactly as the GitHub check did. Runs every few hours and when the app
 * opens. Unlike the upstream GitHub check this is always on: it is how the company reaches its
 * phones, and how a broken version is stopped.
 */
class RemoteConfigWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        RemoteConfig.refresh(applicationContext)
        val preferences = AppPreferences(applicationContext)
        UpdateManager.checkForUpdate(applicationContext)?.let { release ->
            UpdateManager.notifyAvailableOnce(applicationContext, preferences, release.tag)
        }
        Result.success()
    }

    companion object {
        private const val WORK_NAME = "avanegar_remote_config"
        private const val NOW_WORK_NAME = "avanegar_remote_config_now"
        private const val PERIOD_HOURS = 4L
        private const val ON_OPEN_THROTTLE_MS = 15 * 60 * 1000L

        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Keeps the periodic fetch scheduled. Idempotent; call from Application.onCreate. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RemoteConfigWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(connected)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Fetches now unless a fetch finished in the last few minutes; [force] skips that check. */
        fun fetchNow(context: Context, force: Boolean = false) {
            val elapsed = System.currentTimeMillis() - RemoteConfig.lastFetchedMillis(context)
            if (!force && elapsed in 0 until ON_OPEN_THROTTLE_MS) return
            val request = OneTimeWorkRequestBuilder<RemoteConfigWorker>().setConstraints(connected).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW_WORK_NAME,
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
