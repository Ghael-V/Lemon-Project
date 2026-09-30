// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dev.lemon.lemon_emu.features.settings.model.AbstractBooleanSetting

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

    fun setModern(context: Context, modern: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit { putBoolean(KEY, modern) }

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
