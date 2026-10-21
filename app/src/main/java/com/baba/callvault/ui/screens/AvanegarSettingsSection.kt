/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.avanegar.AvanegarClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Which of the section's text settings the edit dialog is open for. */
private enum class AvanegarField { URL, KEY, NAME, SIMS }

/**
 * Settings for sending recordings to the Avanegar server: where, with which device key, under which
 * phone name and SIM numbers, and a check that shows the wallet balance this key can see.
 */
@Composable
internal fun AvanegarSection(preferences: AppPreferences, expanded: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Local re-read trigger: these rows write straight to preferences, not through SettingsActions.
    var version by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<AvanegarField?>(null) }
    var checkStatus by remember { mutableStateOf<String?>(null) }

    val enabled = remember(version) { preferences.isAvanegarUploadEnabled() }
    val url = remember(version) { preferences.getAvanegarServerUrl() }
    val key = remember(version) { preferences.getAvanegarDeviceKey() }
    val deviceName = remember(version) { preferences.getAvanegarDeviceName() }
    val sims = remember(version) { preferences.getAvanegarSimNumbers() }
    val wifiOnly = remember(version) { preferences.isAvanegarWifiOnly() }
    val notSet = stringResource(R.string.avanegar_not_set)
    val needsSetup = url.isEmpty() || key.isEmpty()

    SettingsSection(title = stringResource(R.string.avanegar_section_title), expanded = expanded, onToggle = onToggle) {
        SettingsToggleRow(
            label = stringResource(R.string.avanegar_upload_label),
            description = stringResource(
                if (needsSetup) R.string.avanegar_needs_setup else R.string.avanegar_upload_description
            ),
            checked = enabled && !needsSetup,
            enabled = !needsSetup,
            onCheckedChange = { preferences.setAvanegarUploadEnabled(it); version++ },
            icon = Icons.Filled.Cloud,
        )
        NavigationRow(
            icon = Icons.Filled.Cloud,
            label = stringResource(R.string.avanegar_server_url_label),
            value = url.ifEmpty { notSet },
            onClick = { editing = AvanegarField.URL },
        )
        NavigationRow(
            icon = Icons.Filled.Shield,
            label = stringResource(R.string.avanegar_device_key_label),
            value = if (key.isEmpty()) notSet else stringResource(R.string.avanegar_device_key_set, key.takeLast(4)),
            supporting = stringResource(R.string.avanegar_device_key_hint),
            onClick = { editing = AvanegarField.KEY },
        )
        NavigationRow(
            icon = Icons.Filled.Smartphone,
            label = stringResource(R.string.avanegar_device_name_label),
            value = deviceName,
            onClick = { editing = AvanegarField.NAME },
        )
        NavigationRow(
            icon = Icons.Filled.PhoneInTalk,
            label = stringResource(R.string.avanegar_sims_label),
            value = sims.ifEmpty { notSet },
            supporting = stringResource(R.string.avanegar_sims_hint),
            onClick = { editing = AvanegarField.SIMS },
        )
        SettingsToggleRow(
            label = stringResource(R.string.avanegar_wifi_only_label),
            description = stringResource(R.string.avanegar_wifi_only_description),
            checked = wifiOnly,
            onCheckedChange = { preferences.setAvanegarWifiOnly(it); version++ },
            icon = Icons.Filled.Wifi,
        )
        val checking = stringResource(R.string.avanegar_check_running)
        NavigationRow(
            icon = Icons.Filled.Info,
            label = stringResource(R.string.avanegar_check_label),
            value = checkStatus ?: stringResource(R.string.avanegar_check_value),
            showChevron = false,
            onClick = if (needsSetup) null else {
                {
                    checkStatus = checking
                    scope.launch {
                        checkStatus = withContext(Dispatchers.IO) {
                            runCatching { AvanegarClient(url, key).usage() }.fold(
                                onSuccess = { usage ->
                                    context.getString(
                                        R.string.avanegar_check_ok,
                                        usage.balanceCredits?.let { String.format(Locale.getDefault(), "%,d", it) } ?: "?",
                                        usage.balanceMinutes?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "?",
                                    )
                                },
                                onFailure = { context.getString(R.string.avanegar_check_failed, it.message.orEmpty()) },
                            )
                        }
                    }
                }
            },
        )
    }

    editing?.let { field ->
        val (title, initial, keyboard) = when (field) {
            AvanegarField.URL -> Triple(R.string.avanegar_server_url_label, url, KeyboardType.Uri)
            AvanegarField.KEY -> Triple(R.string.avanegar_device_key_label, key, KeyboardType.Password)
            AvanegarField.NAME -> Triple(R.string.avanegar_device_name_label, deviceName, KeyboardType.Text)
            AvanegarField.SIMS -> Triple(R.string.avanegar_sims_label, sims, KeyboardType.Phone)
        }
        var text by remember(field) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (field) {
                        AvanegarField.URL -> preferences.setAvanegarServerUrl(text)
                        AvanegarField.KEY -> preferences.setAvanegarDeviceKey(text)
                        AvanegarField.NAME -> preferences.setAvanegarDeviceName(text)
                        AvanegarField.SIMS -> preferences.setAvanegarSimNumbers(text)
                    }
                    checkStatus = null
                    editing = null
                    version++
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
