// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.utils

import android.app.Activity
import android.content.Context
import android.view.Window
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import dev.lemon.lemon_emu.features.settings.model.Settings
import dev.lemon.lemon_emu.ui.modern.UiMode

object FullscreenHelper {
    fun isFullscreenEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
            Settings.PREF_APP_FULLSCREEN,
            Settings.APP_FULLSCREEN_DEFAULT
        )
    }

    fun setFullscreenEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(Settings.PREF_APP_FULLSCREEN, enabled)
        }
    }

    fun shouldHideSystemBars(activity: Activity): Boolean {
        val rootInsets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        val barsCurrentlyHidden =
            rootInsets?.isVisible(WindowInsetsCompat.Type.systemBars())?.not() ?: false
        return isFullscreenEnabled(activity) || barsCurrentlyHidden
    }

    fun applyToWindow(window: Window, hideSystemBars: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)

        if (hideSystemBars) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun applyToActivity(activity: Activity) {
        if (UiMode.isModern(activity)) {
            applyImmersiveNavigation(activity.window, hideStatusBar = isFullscreenEnabled(activity))
        } else {
            applyToWindow(activity.window, isFullscreenEnabled(activity))
        }
    }

    /**
     * Sticky immersive for the redesigned interface: the navigation bar is always hidden and only
     * appears for a moment when the user swipes in from the edge. The status bar (clock, battery)
     * stays unless the "fullscreen" setting asks to hide it as well.
     */
    fun applyImmersiveNavigation(window: Window, hideStatusBar: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        if (hideStatusBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}
