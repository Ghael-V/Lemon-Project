// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.lemon.lemon_emu.features.settings.model

import dev.lemon.lemon_emu.utils.NativeConfig

enum class StringSetting(override val key: String) : AbstractStringSetting {
    DRIVER_PATH("driver_path"),
    DEVICE_NAME("device_name"),
    LOG_FILTER("log_filter"),
    PROGRAM_ARGS("program_args"),
    POST_SHADER_CHAIN("post_shader_chain"),
    HOTKEY_QUICK_SAVE("hotkey_quick_save"),
    HOTKEY_QUICK_LOAD("hotkey_quick_load"),
    HOTKEY_LOAD_SLOT("hotkey_load_slot"),
    HOTKEY_TOGGLE_STATS_OVERLAY("hotkey_toggle_stats_overlay"),
    HOTKEY_TOGGLE_SOC_OVERLAY("hotkey_toggle_soc_overlay"),
    HOTKEY_PAUSE_EMULATION("hotkey_pause_emulation"),
    HOTKEY_EXIT_EMULATION("hotkey_exit_emulation"),
    HOTKEY_ENABLE_BUTTON("hotkey_enable_button"),

    WEB_TOKEN("lemon_token"),
    WEB_USERNAME("lemon_username")
    ;

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
