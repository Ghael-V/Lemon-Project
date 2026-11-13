// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings.model

import dev.ironlemon.ironlemon_emulator.utils.NativeConfig

enum class StringSetting(override val key: String) : AbstractStringSetting {
    DRIVER_PATH("driver_path"),
    DEVICE_NAME("device_name"),
    HOTKEY_QUICK_SAVE("hotkey_quick_save"),
    HOTKEY_QUICK_LOAD("hotkey_quick_load"),
    HOTKEY_TOGGLE_STATS_OVERLAY("hotkey_toggle_stats_overlay"),
    HOTKEY_TOGGLE_SOC_OVERLAY("hotkey_toggle_soc_overlay"),
    HOTKEY_PAUSE_EMULATION("hotkey_pause_emulation"),
    HOTKEY_EXIT_EMULATION("hotkey_exit_emulation"),
    HOTKEY_ENABLE_BUTTON("hotkey_enable_button");

    override fun getString(needsGlobal: Boolean): String = NativeConfig.getString(key, needsGlobal)

    override fun setString(value: String) {
        if (NativeConfig.isPerGameConfigLoaded()) {
            global = false
        }
        NativeConfig.setString(key, value)
    }

    override val defaultValue: String by lazy { NativeConfig.getDefaultToString(key) }

    override fun getValueAsString(needsGlobal: Boolean): String = getString(needsGlobal)

    override fun reset() = NativeConfig.setString(key, defaultValue)
}
