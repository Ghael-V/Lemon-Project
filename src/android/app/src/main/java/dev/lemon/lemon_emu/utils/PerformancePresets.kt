// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.utils

import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.features.settings.model.AbstractSetting
import dev.lemon.lemon_emu.features.settings.model.BooleanSetting
import dev.lemon.lemon_emu.features.settings.model.IntSetting

/**
 * Modes: the emulator set up as a whole in one tap.
 * - Speed: lighter settings for weaker devices (what Lemon Lite used to start with).
 * - Normal: the default settings. A fresh install is in Normal, and Normal puts every setting a
 *   mode touches back to its default, so the two can't drift apart.
 * - Quality: a sharper image for strong devices.
 * Every setting a mode touches can still be changed one by one; the active mode is simply the one
 * whose values the settings hold (none when they were tuned by hand). Applied to the config loaded
 * now: the global one, or a game's custom config.
 */
object PerformancePresets {
    // Shared preference key for the adaptive-performance toggle (on by default - a device with
    // its own active cooling simply won't hit the thermal trigger, and the notification posted
    // when either trigger fires always points back to this toggle for anyone who wants it off).
    const val PREF_ADAPTIVE_PERFORMANCE = "adaptive_performance_enabled"

    enum class Preset(val titleRes: Int, val descriptionRes: Int) {
        SPEED(R.string.preset_speed, R.string.preset_speed_description),
        NORMAL(R.string.preset_normal, R.string.preset_normal_description),
        QUALITY(R.string.preset_quality, R.string.preset_quality_description)
    }

    /** A mode's value for one setting; null means the setting's default. */
    private sealed interface Value {
        val setting: AbstractSetting

        data class IntValue(override val setting: IntSetting, val value: Int?) : Value
        data class BoolValue(override val setting: BooleanSetting, val value: Boolean?) : Value
    }

    private fun int(setting: IntSetting, value: Int?) = Value.IntValue(setting, value)
    private fun bool(setting: BooleanSetting, value: Boolean?) = Value.BoolValue(setting, value)

    // Numbers are the core's enums (common/settings_enums.h): ResolutionSetup Res3_4X = 2,
    // Res2X = 6; ScalingFilter Bilinear = 1, Bicubic = 2; AntiAliasing None = 0, Fxaa = 1;
    // GpuAccuracy Low = 0, High = 1.
    private fun values(preset: Preset): List<Value> = when (preset) {
        Preset.SPEED -> listOf(
            int(IntSetting.RENDERER_RESOLUTION, 2),
            int(IntSetting.RENDERER_SCALING_FILTER, 1),
            int(IntSetting.RENDERER_ANTI_ALIASING, 0),
            int(IntSetting.RENDERER_ACCURACY, 0),
            bool(BooleanSetting.RENDERER_ASYNCHRONOUS_SHADERS, true)
        )
        Preset.NORMAL -> listOf(
            int(IntSetting.RENDERER_RESOLUTION, null),
            int(IntSetting.RENDERER_SCALING_FILTER, null),
            int(IntSetting.RENDERER_ANTI_ALIASING, null),
            int(IntSetting.RENDERER_ACCURACY, null),
            bool(BooleanSetting.RENDERER_ASYNCHRONOUS_SHADERS, null)
        )
        Preset.QUALITY -> listOf(
            int(IntSetting.RENDERER_RESOLUTION, 6),
            int(IntSetting.RENDERER_SCALING_FILTER, 2),
            int(IntSetting.RENDERER_ANTI_ALIASING, 1),
            int(IntSetting.RENDERER_ACCURACY, 1),
            bool(BooleanSetting.RENDERER_ASYNCHRONOUS_SHADERS, false)
        )
    }

    /** Sets the mode's values in the config loaded now (the global one, or a game's). */
    fun apply(preset: Preset) {
        values(preset).forEach {
            when (it) {
                is Value.IntValue -> it.setting.setInt(it.value ?: it.setting.defaultValue)
                is Value.BoolValue -> it.setting.setBoolean(it.value ?: it.setting.defaultValue)
            }
        }
    }

    /** The mode the loaded config's values match, or null if they were tuned by hand. */
    fun current(): Preset? = Preset.entries.firstOrNull { preset ->
        values(preset).all {
            when (it) {
                is Value.IntValue -> it.setting.getInt() == (it.value ?: it.setting.defaultValue)
                is Value.BoolValue ->
                    it.setting.getBoolean() == (it.value ?: it.setting.defaultValue)
            }
        }
    }
}
