// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings.model

import dev.ironlemon.ironlemon_emulator.utils.NativeConfig

interface AbstractSetting {
    val key: String
    val defaultValue: Any

    // Headers, submenus, buttons and input items have no native key: answer for them here, with
    // what the core answers for an unknown key, instead of asking (and logging an error) each time.
    val isRuntimeModifiable: Boolean
        get() = key.isEmpty() || NativeConfig.getIsRuntimeModifiable(key)

    val pairedSettingKey: String
        get() = if (key.isEmpty()) "" else NativeConfig.getPairedSettingKey(key)

    val isSwitchable: Boolean
        get() = key.isNotEmpty() && NativeConfig.getIsSwitchable(key)

    var global: Boolean
        get() = key.isEmpty() || NativeConfig.usingGlobal(key)
        set(value) {
            if (key.isNotEmpty()) {
                NativeConfig.setGlobal(key, value)
            }
        }

    val isSaveable: Boolean
        get() = key.isNotEmpty() && NativeConfig.getIsSaveable(key)

    fun getValueAsString(needsGlobal: Boolean = false): String

    fun reset()
}
