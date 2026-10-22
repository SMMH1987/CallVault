/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.Context
import android.content.pm.PackageManager
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.security.MessageDigest

/**
 * Verifies a downloaded update APK BEFORE it is handed to any installer:
 *  1. it is a readable APK for OUR package name,
 *  2. its versionCode is strictly higher than the running app's,
 *  3. it is signed with the same key as the installed app.
 *
 * Android itself refuses mismatched-cert updates, but verifying first fails cleanly (with a clear
 * log + user-facing error) instead of a confusing system installer failure — and protects the
 * silent shell-install path, which would otherwise happily run `pm install` on anything.
 */
object ApkVerifier {

    private const val TAG = "CV:ApkVerifier"

    /**
     * This fork is signed with its own key, not upstream's, so the expected certificate is the one
     * the installed app carries: an update must come from the same signer as the build it replaces.
     * Android enforces the same rule; checking first keeps the silent shell install from running on
     * anything else, and keeps the user's settings, which only survive a same-key update.
     */
    private fun installedCertSha256(context: Context): Set<String> = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return emptySet()
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        certs.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
    }.getOrDefault(emptySet())

    /** True when [apkFile] is a genuine CallVault update: right package, newer, right signature. */
    fun isValidUpdate(context: Context, apkFile: File): Boolean {
        if (!apkFile.isFile || apkFile.length() == 0L) {
            AppLogger.w(TAG, "Update APK missing or empty: ${apkFile.path}")
            return false
        }
        val packageManager = context.packageManager
        val info = runCatching {
            @Suppress("DEPRECATION") // (Path-based overload; the flags variant needs API 33+)
            packageManager.getPackageArchiveInfo(apkFile.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()
        if (info == null) {
            AppLogger.w(TAG, "Update APK could not be parsed")
            return false
        }

        if (info.packageName != context.packageName) {
            AppLogger.w(TAG, "Update APK package mismatch: ${info.packageName}")
            return false
        }

        val installedVersionCode = runCatching {
            packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(Long.MAX_VALUE)
        if (info.longVersionCode <= installedVersionCode) {
            AppLogger.w(TAG, "Update APK is not newer: ${info.longVersionCode} <= $installedVersionCode")
            return false
        }

        val signingInfo = info.signingInfo
        val signers = signingInfo?.apkContentsSigners
        if (signingInfo == null || signers.isNullOrEmpty()) {
            AppLogger.w(TAG, "Update APK has no readable signature")
            return false
        }
        // Require exactly one signer, and it must be this install's key; anything multiply-signed is not ours.
        if (signingInfo.hasMultipleSigners() || signers.size != 1) {
            AppLogger.e(TAG, "Update APK has an unexpected signer set — refusing to install")
            return false
        }
        val matches = sha256Hex(signers[0].toByteArray()) in installedCertSha256(context)
        if (!matches) {
            AppLogger.e(TAG, "Update APK is signed with a different key than this install — refusing to install")
        }
        return matches
    }

    /** Lowercase hex SHA-256 of [file]'s contents. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
