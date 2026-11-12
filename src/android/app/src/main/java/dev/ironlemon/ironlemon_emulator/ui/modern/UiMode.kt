// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project / Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.ui.modern

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dev.ironlemon.ironlemon_emulator.features.settings.model.AbstractBooleanSetting

/**
 * Which interface Lemon shows: the new one or the classic one. Kept in the app's own preferences
 * (not the emulator's native config) so switching it never touches the emulation core, and read
 * every time a screen is created, so the change applies as soon as the user goes back to it.
 */
object UiMode {
    const val KEY = "ui_modern"
    private const val DEFAULT_MODERN = true

    fun isModern(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .getBoolean(KEY, DEFAULT_MODERN)

    fun setModern(context: Context, modern: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit { putBoolean(KEY, modern) }
        applyDefaultNightMode(context)
    }

    /**
     * The new interface is dark only. Making dark the app's default before any screen exists
     * lets every screen start dark, instead of switching to dark in place after it's created: the
     * emulation screen handles that switch itself and kept the light theme's text colours.
     */
    fun applyDefaultNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (isModern(context)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** The same switch as a settings item, for the Settings screen. */
    fun asSetting(context: Context): AbstractBooleanSetting = object : AbstractBooleanSetting {
        override val key = KEY
        override val defaultValue = DEFAULT_MODERN
        // Not a native setting: never ask the emulation core about this key.
        override val isRuntimeModifiable = true
        override val pairedSettingKey = ""
        override val isSwitchable = false
        override val isSaveable = false
        override var global = true
        override fun getBoolean(needsGlobal: Boolean) = isModern(context)
        override fun setBoolean(value: Boolean) = setModern(context, value)
        override fun getValueAsString(needsGlobal: Boolean) = isModern(context).toString()
        override fun reset() = setModern(context, DEFAULT_MODERN)
    }
}
