// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import android.app.Activity
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Iron Lemon is always full screen: the status and navigation bars stay hidden, and a swipe from
 * the edge only shows them for a moment. Call it from onCreate and again whenever the window gets
 * focus back (a dialog or the system can bring the bars back).
 */
object FullscreenHelper {
    fun hideSystemBars(activity: Activity) = hideSystemBars(activity.window)

    /** The same for a dialog's own window, which would otherwise bring the bars back. */
    fun hideSystemBars(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
