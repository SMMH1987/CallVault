/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.baba.callvault.R

/**
 * What the app says when on-device transcription or summarisation is asked for in a build without it
 * (BuildConfig.ON_DEVICE_AI = false). The buttons stay, so a tap gets an answer instead of nothing.
 */
object OnDeviceAi {
    fun explainUnavailable(context: Context) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, R.string.on_device_ai_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}
