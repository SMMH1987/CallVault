/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.integrations.hub.Hub
import com.baba.callvault.integrations.hub.HubUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private sealed interface HubCardState {
    data object Loading : HubCardState
    data object Waiting : HubCardState
    data object Error : HubCardState
    data class Ready(val summary: JSONObject, val history: Int) : HubCardState
}

/**
 * The server's structured summary of a call sent to the Hamdabir hub, with how many earlier calls the
 * company had with the same number. Shows nothing when the hub is not set up or the call was not sent.
 */
@Composable
fun HubCallCard(recordingName: String) {
    val context = LocalContext.current
    val callId = remember(recordingName) { HubUploadWorker.callIdFor(context, recordingName) }
    val client = remember { Hub.client(context) }
    if (callId == null || client == null) return
    var reload by remember { mutableIntStateOf(0) }
    var state by remember(recordingName) { mutableStateOf<HubCardState>(HubCardState.Loading) }
    LaunchedEffect(recordingName, reload) {
        state = HubCardState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching {
                val detail = client.callDetail(callId)
                val summary = detail.optJSONObject("summary")
                if (summary == null) HubCardState.Waiting
                else HubCardState.Ready(summary, (client.customerCalls(detail.getString("phone")).length() - 1).coerceAtLeast(0))
            }.getOrElse { HubCardState.Error }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.hub_card_title), style = MaterialTheme.typography.titleMedium)
            when (val s = state) {
                HubCardState.Loading -> Text(stringResource(R.string.hub_card_loading))
                HubCardState.Waiting -> {
                    Text(stringResource(R.string.hub_card_waiting))
                    TextButton(onClick = { reload++ }) { Text(stringResource(R.string.hub_card_retry)) }
                }
                HubCardState.Error -> {
                    Text(stringResource(R.string.hub_card_error))
                    TextButton(onClick = { reload++ }) { Text(stringResource(R.string.hub_card_retry)) }
                }
                is HubCardState.Ready -> ReadyHubSummary(s.summary, s.history)
            }
        }
    }
}

@Composable
private fun ReadyHubSummary(s: JSONObject, history: Int) {
    fun str(key: String) = s.optString(key).takeIf { it.isNotBlank() && it != "null" }
    Text(str("overview") ?: str("text").orEmpty(), modifier = Modifier.padding(top = 8.dp))
    str("result")?.let { Text(stringResource(R.string.hub_card_result, it), modifier = Modifier.padding(top = 4.dp)) }
    str("next_step")?.let { Text(stringResource(R.string.hub_card_next, it), modifier = Modifier.padding(top = 4.dp)) }
    val objections = s.optJSONArray("objections")
    if (objections != null && objections.length() > 0) {
        val list = (0 until objections.length()).joinToString("، ") { objections.optString(it) }
        Text(stringResource(R.string.hub_card_objections, list), modifier = Modifier.padding(top = 4.dp))
    }
    val tasks = s.optJSONArray("tasks")
    if (tasks != null && tasks.length() > 0) {
        Text(stringResource(R.string.hub_card_tasks), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp))
        for (i in 0 until tasks.length()) {
            val t = tasks.opt(i)
            val line = if (t is JSONObject) listOfNotNull(t.optString("title"), t.optString("due").ifBlank { null })
                .joinToString(" · ") else t.toString()
            Text("• $line")
        }
    }
    if (history > 0) Text(stringResource(R.string.hub_card_history, history), modifier = Modifier.padding(top = 8.dp))
}
