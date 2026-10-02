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
    // Shared preference key for the adaptive-performance toggle (on by default - a device with
    // its own active cooling simply won't hit the thermal trigger, and the notification posted
    // when either trigger fires always points back to this toggle for anyone who wants it off).
    const val PREF_ADAPTIVE_PERFORMANCE = "adaptive_performance_enabled"

    enum class Preset(val titleRes: Int) {
        BATTERY(R.string.preset_battery),
        BALANCED(R.string.preset_balanced),
        QUALITY(R.string.preset_quality)
    }

    private class Values(
        val resolution: Int,
        val scalingFilter: Int,
        val antiAliasing: Int,
        val accuracy: Int,
        val vsync: Int
    )

    private fun valuesOf(preset: Preset) = when (preset) {
        Preset.BATTERY -> Values(1, 1, 0, 0, 2) // 0.5x, bilinear, no AA, low accuracy, Fifo
        Preset.BALANCED -> Values(3, 1, 0, 1, 2) // native, bilinear, no AA, high accuracy, Fifo (the stock settings)
        Preset.QUALITY -> Values(6, 2, 1, 1, 2) // 2x, bicubic, FXAA, high accuracy, Fifo
    }

    fun apply(preset: Preset) {
        val v = valuesOf(preset)
        IntSetting.RENDERER_RESOLUTION.setInt(v.resolution)
        IntSetting.RENDERER_SCALING_FILTER.setInt(v.scalingFilter)
        IntSetting.RENDERER_ANTI_ALIASING.setInt(v.antiAliasing)
        IntSetting.RENDERER_ACCURACY.setInt(v.accuracy)
        IntSetting.RENDERER_VSYNC.setInt(v.vsync)
    }

    /** The preset the current settings match exactly, or null if they were tuned by hand. */
    fun current(): Preset? = Preset.entries.firstOrNull { preset ->
        val v = valuesOf(preset)
        IntSetting.RENDERER_RESOLUTION.getInt() == v.resolution &&
            IntSetting.RENDERER_SCALING_FILTER.getInt() == v.scalingFilter &&
            IntSetting.RENDERER_ANTI_ALIASING.getInt() == v.antiAliasing &&
            IntSetting.RENDERER_ACCURACY.getInt() == v.accuracy &&
            IntSetting.RENDERER_VSYNC.getInt() == v.vsync
    }
}
