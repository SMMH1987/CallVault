/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import com.baba.callvault.BuildConfig
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.remoteconfig.RemoteConfig
import com.baba.callvault.system.remoteconfig.RemoteConfigWorker
import com.baba.callvault.system.updates.UpdateInstallWorker
import com.baba.callvault.system.updates.UpdateScheduler
import com.baba.callvault.ui.theme.CallVaultTheme

/**
 * Stands between the app and its screens. When the app config says this version may no longer be
 * used, the screens are replaced by a page that explains why and installs the new version, the way
 * Divar does. Otherwise the app draws as usual, with any new message from the company on top.
 *
 * Recording is not stopped by a block: the recorder runs in its own service, and a missed call is
 * worse than an old version. Only the screens wait for the update.
 */
@Composable
fun RemoteConfigGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val config by RemoteConfig.state.collectAsState()
    val current = config
    if (current != null && current.isBlocked()) {
        ForcedUpdateScreen(current)
        return
    }
    content()

    var message by remember(current) { mutableStateOf(RemoteConfig.nextMessage(context, current)) }
    message?.let { shown ->
        CallVaultTheme {
            InAppMessageDialog(shown) {
                RemoteConfig.markMessageSeen(context, shown.id)
                message = RemoteConfig.nextMessage(context, current)
            }
        }
    }
}

@Composable
private fun ForcedUpdateScreen(config: RemoteConfig.Config) {
    val context = LocalContext.current
    val update = config.update?.takeIf { it.versionCode > BuildConfig.VERSION_CODE.toLong() }
    val installs by remember {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UpdateScheduler.INSTALL_WORK_NAME)
    }.collectAsState(initial = emptyList())
    val running = installs.firstOrNull { !it.state.isFinished }
    val percent = running?.progress?.getInt(UpdateInstallWorker.KEY_PROGRESS, -1) ?: -1

    CallVaultTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = config.block.title ?: stringResource(R.string.avanegar_blocked_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = config.block.body ?: stringResource(R.string.avanegar_blocked_body),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                if (update != null && update.changelog.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.avanegar_update_whats_new, update.versionName),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(text = update.changelog, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(24.dp))
                when {
                    running != null -> {
                        Text(stringResource(R.string.avanegar_update_downloading))
                        Spacer(Modifier.height(8.dp))
                        if (percent >= 0) {
                            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    update != null -> Button(
                        onClick = {
                            AppPreferences(context).setUpdateInstallArmed(true)
                            UpdateScheduler.enqueueInstallNow(context)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.avanegar_update_install, update.versionName)) }
                    else -> Button(
                        onClick = { RemoteConfigWorker.fetchNow(context, force = true) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.avanegar_update_retry)) }
                }
                val directUrl = update?.pageUrl ?: update?.apkUrl
                if (directUrl != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { openUrl(context, directUrl) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.avanegar_update_direct)) }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.avanegar_update_installed_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun InAppMessageDialog(message: RemoteConfig.Message, onDone: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (message.level != "critical") onDone() },
        title = if (message.title.isNotBlank()) {
            { Text(message.title) }
        } else null,
        text = { Text(message.body) },
        confirmButton = {
            val url = message.actionUrl
            if (url != null) {
                TextButton(onClick = {
                    openUrl(context, url)
                    onDone()
                }) { Text(message.actionLabel ?: stringResource(R.string.avanegar_message_open)) }
            } else {
                TextButton(onClick = onDone) { Text(stringResource(R.string.avanegar_message_ok)) }
            }
        },
        dismissButton = if (message.actionUrl != null) {
            { TextButton(onClick = onDone) { Text(stringResource(R.string.avanegar_message_later)) } }
        } else null
    )
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
