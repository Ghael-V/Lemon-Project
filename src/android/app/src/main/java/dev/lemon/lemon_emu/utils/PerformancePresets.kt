// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.utils

import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.features.settings.model.IntSetting

// Known-good combinations of settings a user could already reach individually via
// Advanced Settings > Graphics - this is a shortcut, not a new tuning behavior.
// Shared between the global preset (HomeSettingsFragment) and the per-game override
// (GameAdapter's quick menu) so both write the exact same values.
object PerformancePresets {
    // Shared preference key for the opt-in thermal auto-throttle toggle (off by default -
    // devices with active cooling, e.g. gaming handhelds, may not want this at all).
    const val PREF_THERMAL_AUTO_THROTTLE = "thermal_auto_throttle_enabled"

    enum class Preset(val titleRes: Int) {
        BATTERY(R.string.preset_battery),
        BALANCED(R.string.preset_balanced),
        QUALITY(R.string.preset_quality)
    }

    fun apply(preset: Preset) {
        when (preset) {
            Preset.BATTERY -> {
                IntSetting.RENDERER_RESOLUTION.setInt(1) // Res1_2X (50%)
                IntSetting.RENDERER_SCALING_FILTER.setInt(1) // Bilinear
                IntSetting.RENDERER_ANTI_ALIASING.setInt(0) // None
                IntSetting.RENDERER_ACCURACY.setInt(0) // Low
                IntSetting.RENDERER_VSYNC.setInt(2) // Fifo
            }

            Preset.BALANCED -> {
                IntSetting.RENDERER_RESOLUTION.setInt(3) // Res1X (native)
                IntSetting.RENDERER_SCALING_FILTER.setInt(1) // Bilinear
                IntSetting.RENDERER_ANTI_ALIASING.setInt(0) // None
                IntSetting.RENDERER_ACCURACY.setInt(0) // Low
                IntSetting.RENDERER_VSYNC.setInt(2) // Fifo
            }

            Preset.QUALITY -> {
                IntSetting.RENDERER_RESOLUTION.setInt(6) // Res2X
                IntSetting.RENDERER_SCALING_FILTER.setInt(2) // Bicubic
                IntSetting.RENDERER_ANTI_ALIASING.setInt(1) // Fxaa
                IntSetting.RENDERER_ACCURACY.setInt(1) // High
                IntSetting.RENDERER_VSYNC.setInt(2) // Fifo
            }
        }
    }
}
