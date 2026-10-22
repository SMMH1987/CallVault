/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.remoteconfig

import com.baba.callvault.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteConfigTest {

    private val code = BuildConfig.VERSION_CODE.toLong()

    @Test
    fun full_file_parses() {
        val config = RemoteConfig.parse(
            """
            {
              "update": {"version_code": ${code + 1}, "version_name": "9.9.9",
                         "apk_url": "https://example.com/a.apk", "apk_size": 10,
                         "apk_sha256": "${"a".repeat(64)}", "critical": true},
              "versions": {"min_supported_code": 1, "blocked_codes": [$code]},
              "messages": [{"id": "m1", "body": "hello"}],
              "features": {"wallet": true}
            }
            """
        )
        assertNotNull(config)
        config!!
        assertEquals(code + 1, config.update?.versionCode)
        assertTrue(config.update!!.critical)
        assertTrue(config.isBlocked())
        assertEquals("m1", config.messages.single().id)
        assertTrue(config.feature("wallet", false))
        assertFalse(config.feature("missing", false))
    }

    @Test
    fun min_supported_code_blocks_older_builds_only() {
        val config = RemoteConfig.parse("""{"versions": {"min_supported_code": ${code + 1}}}""")!!
        assertTrue(config.isBlocked())
        assertFalse(config.isBlocked(code + 1))
    }

    @Test
    fun cleartext_apk_url_drops_the_update() {
        val config = RemoteConfig.parse(
            """{"update": {"version_code": 5, "apk_url": "http://example.com/a.apk", "apk_size": 10}}"""
        )!!
        assertNull(config.update)
    }

    @Test
    fun channel_block_overrides_top_level() {
        val config = RemoteConfig.parse(
            """{"versions": {"min_supported_code": 0},
                "channels": {"${BuildConfig.BUILD_CHANNEL}": {"versions": {"min_supported_code": ${code + 1}}}}}"""
        )!!
        assertTrue(config.isBlocked())
    }

    @Test
    fun garbage_is_not_a_config() {
        assertNull(RemoteConfig.parse("not json"))
    }
}
