/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.remoteconfig

import android.content.Context
import androidx.core.content.edit
import com.baba.callvault.BuildConfig
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one config file this fork reads from outside: the latest version and where to get it, the
 * oldest version still allowed to run, messages to show in the app, settings the company manages
 * centrally, and feature switches. See docs/avanegar-app-config.md for the format.
 *
 * It is fetched from a list of addresses, first success wins: the address typed in Settings, the
 * addresses the last good config named, our server's `/app/config.json`, then the GitHub copy. The
 * last good copy is kept, so a phone that is offline still knows its version is blocked.
 */
object RemoteConfig {

    private const val TAG = "CV:RemoteConfig"
    private const val PREFS = "avanegar_remote_config"
    private const val KEY_JSON = "json"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val KEY_SOURCE = "source"
    private const val KEY_SEEN_MESSAGES = "seen_messages"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_BYTES = 256 * 1024

    data class Update(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val apkSha256: String?,
        val apkSizeBytes: Long,
        val changelog: String,
        val critical: Boolean,
        val pageUrl: String?
    )

    data class Message(
        val id: String,
        val title: String,
        val body: String,
        /** "info", "warning" or "critical". */
        val level: String,
        val actionLabel: String?,
        val actionUrl: String?,
        val untilMillis: Long?,
        val minVersionCode: Long,
        val maxVersionCode: Long,
        val channels: Set<String>
    )

    data class Block(val title: String?, val body: String?)

    data class Config(
        val update: Update?,
        /** Versions below this may not be used; the app shows the update screen instead. */
        val minVersionCode: Long,
        val blockedVersionCodes: Set<Long>,
        val block: Block,
        val messages: List<Message>,
        val settings: JSONObject,
        val features: JSONObject,
        val configUrls: List<String>
    ) {
        /** True when this installed build must not be used until it is updated. */
        fun isBlocked(versionCode: Long = BuildConfig.VERSION_CODE.toLong()): Boolean =
            versionCode < minVersionCode || versionCode in blockedVersionCodes

        fun feature(name: String, default: Boolean): Boolean =
            if (features.has(name)) features.optBoolean(name, default) else default
    }

    private val _state = MutableStateFlow<Config?>(null)

    /** The newest config this process knows of; null until one has been fetched or read from disk. */
    val state: StateFlow<Config?> = _state.asStateFlow()

    /** Loads the stored copy into [state]. Cheap; safe on the main thread at start. */
    fun load(context: Context): Config? {
        _state.value?.let { return it }
        val json = prefs(context).getString(KEY_JSON, null) ?: return null
        return parse(json)?.also { _state.value = it }
    }

    /**
     * Fetches the config from the first address that answers, stores it, and applies the managed
     * settings. Blocking network I/O; worker thread only.
     * @return the fresh config, or null when no address answered with a valid file.
     */
    fun refresh(context: Context): Config? {
        val preferences = AppPreferences(context)
        for (url in candidateUrls(context, preferences)) {
            val body = fetch(url) ?: continue
            val config = parse(body) ?: run {
                AppLogger.w(TAG, "Config at $url did not parse; trying the next address")
                null
            } ?: continue
            prefs(context).edit {
                putString(KEY_JSON, body)
                putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                putString(KEY_SOURCE, url)
            }
            _state.value = config
            applyManagedSettings(preferences, config.settings)
            AppLogger.i(TAG, "Config loaded from $url")
            return config
        }
        AppLogger.w(TAG, "No config address answered; keeping the stored copy")
        return null
    }

    fun lastFetchedMillis(context: Context): Long = prefs(context).getLong(KEY_FETCHED_AT, 0L)

    fun lastSource(context: Context): String? = prefs(context).getString(KEY_SOURCE, null)

    /** The first message this build should show and has not shown yet, or null. */
    fun nextMessage(context: Context, config: Config?): Message? {
        config ?: return null
        val seen = seenMessages(context)
        val now = System.currentTimeMillis()
        val code = BuildConfig.VERSION_CODE.toLong()
        return config.messages.firstOrNull { message ->
            message.id !in seen &&
                (message.untilMillis == null || now < message.untilMillis) &&
                code in message.minVersionCode..message.maxVersionCode &&
                (message.channels.isEmpty() || BuildConfig.BUILD_CHANNEL in message.channels)
        }
    }

    fun markMessageSeen(context: Context, id: String) {
        val seen = seenMessages(context) + id
        prefs(context).edit { putStringSet(KEY_SEEN_MESSAGES, seen) }
    }

    private fun seenMessages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SEEN_MESSAGES, emptySet()).orEmpty().toSet()

    /** Addresses to try, in order, without duplicates. Only https is ever fetched. */
    fun candidateUrls(context: Context, preferences: AppPreferences = AppPreferences(context)): List<String> {
        val urls = mutableListOf<String>()
        urls += preferences.getAvanegarConfigUrl()
        load(context)?.configUrls?.let { urls += it }
        preferences.getAvanegarServerUrl().takeIf { it.isNotBlank() }?.let { urls += "$it/app/config.json" }
        urls += BuildConfig.CONFIG_FALLBACK_URL
        return urls.map { it.trim() }.filter { it.startsWith("https://") }.distinct()
    }

    private fun fetch(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "CallVault-Avanegar/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}; ${BuildConfig.BUILD_CHANNEL})")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                AppLogger.w(TAG, "Config fetch $url: HTTP ${connection.responseCode}")
                return null
            }
            connection.inputStream.use { input ->
                val bytes = input.readNBytesCompat(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) {
                    AppLogger.w(TAG, "Config at $url is larger than $MAX_BYTES bytes; ignored")
                    return null
                }
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }.getOrElse { e ->
        AppLogger.w(TAG, "Config fetch $url failed: ${e.message}")
        null
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Parses a config file; null when it is not one. Unknown fields are ignored. */
    fun parse(json: String): Config? = runCatching {
        val root = JSONObject(json)
        val channel = BuildConfig.BUILD_CHANNEL
        // A per-build override block ("channels": {"test": {...}}) replaces top-level fields.
        val scoped = root.optJSONObject("channels")?.optJSONObject(channel)
        fun section(name: String): JSONObject? = scoped?.optJSONObject(name) ?: root.optJSONObject(name)

        val update = section("update")?.let(::parseUpdate)
        val versions = section("versions") ?: JSONObject()
        Config(
            update = update,
            minVersionCode = versions.optLong("min_supported_code", 0L),
            blockedVersionCodes = versions.optJSONArray("blocked_codes").longs().toSet(),
            block = Block(
                title = versions.optString("block_title").ifBlank { null },
                body = versions.optString("block_body").ifBlank { null }
            ),
            messages = (scoped?.optJSONArray("messages") ?: root.optJSONArray("messages")).objects()
                .mapNotNull(::parseMessage),
            settings = section("settings") ?: JSONObject(),
            features = section("features") ?: JSONObject(),
            configUrls = root.optJSONArray("config_urls").strings()
        )
    }.getOrElse { e ->
        AppLogger.w(TAG, "Config parse failed: ${e.message}")
        null
    }

    private fun parseUpdate(o: JSONObject): Update? {
        val code = o.optLong("version_code", 0L).takeIf { it > 0L } ?: return null
        val url = o.optString("apk_url").takeIf { it.startsWith("https://") } ?: return null
        val size = o.optLong("apk_size", 0L).takeIf { it > 0L } ?: return null
        return Update(
            versionCode = code,
            versionName = o.optString("version_name").ifBlank { code.toString() },
            apkUrl = url,
            apkSha256 = o.optString("apk_sha256").lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) },
            apkSizeBytes = size,
            changelog = o.optString("changelog"),
            critical = o.optBoolean("critical", false),
            pageUrl = o.optString("page_url").takeIf { it.startsWith("https://") }
        )
    }

    private fun parseMessage(o: JSONObject): Message? {
        val id = o.optString("id").ifBlank { return null }
        val body = o.optString("body").ifBlank { return null }
        return Message(
            id = id,
            title = o.optString("title"),
            body = body,
            level = o.optString("level", "info"),
            actionLabel = o.optString("action_label").ifBlank { null },
            actionUrl = o.optString("action_url").takeIf { it.startsWith("https://") || it.startsWith("tg://") },
            untilMillis = o.optString("until").ifBlank { null }?.let(::parseDate),
            minVersionCode = o.optLong("min_version_code", 0L),
            maxVersionCode = o.optLong("max_version_code", Long.MAX_VALUE),
            channels = o.optJSONArray("channels").strings().toSet()
        )
    }

    /** "2026-12-01" or a full ISO instant; null when neither. */
    private fun parseDate(raw: String): Long? = runCatching {
        if (raw.length == 10) java.time.LocalDate.parse(raw).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        else java.time.Instant.parse(raw).toEpochMilli()
    }.getOrNull()

    /**
     * Writes the settings the company manages centrally. Only keys present in the file are touched,
     * so an empty `settings` block changes nothing on the phone.
     */
    private fun applyManagedSettings(preferences: AppPreferences, settings: JSONObject) {
        if (settings.has("avanegar_server_url")) {
            settings.optString("avanegar_server_url").takeIf { it.startsWith("https://") }
                ?.let(preferences::setAvanegarServerUrl)
        }
        if (settings.has("avanegar_upload_enabled") && preferences.getAvanegarDeviceKey().isNotBlank()) {
            preferences.setAvanegarUploadEnabled(settings.optBoolean("avanegar_upload_enabled"))
        }
        if (settings.has("avanegar_wifi_only")) {
            preferences.setAvanegarWifiOnly(settings.optBoolean("avanegar_wifi_only"))
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }

    private fun JSONArray?.longs(): List<Long> =
        if (this == null) emptyList() else (0 until length()).map { optLong(it) }.filter { it > 0L }
}
