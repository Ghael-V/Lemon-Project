// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import androidx.annotation.StringRes
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.settings.model.AbstractSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.BooleanSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.IntSetting
import dev.ironlemon.ironlemon_emulator.features.settings.utils.SettingsFile
import dev.ironlemon.ironlemon_emulator.model.Game

/**
 * Modes: the emulator set up as a whole in one tap.
 * - Speed: the lowest everything goes, for weak devices, whatever the image looks like.
 * - Normal: the default settings, a good balance of speed and image.
 * - Quality: the best image the emulator makes, whatever it costs.
 * Every setting a mode touches can still be changed one by one. A mode is not stored anywhere: it
 * is the values it sets, and the active one is the one whose values the settings hold. Applied
 * globally, or to one game through its custom config like any per-game setting.
 */
object PerformancePresets {
    enum class Preset(@StringRes val titleRes: Int, @StringRes val descriptionRes: Int) {
        SPEED(R.string.preset_speed, R.string.preset_speed_description),
        NORMAL(R.string.preset_normal, R.string.preset_normal_description),
        QUALITY(R.string.preset_quality, R.string.preset_quality_description)
    }

    /** A value of a mode: a number for an enum setting, or a switch. Null: the setting's default. */
    private sealed interface Value {
        data class IntValue(val setting: IntSetting, val value: Int?) : Value
        data class BoolValue(val setting: BooleanSetting, val value: Boolean?) : Value
    }

    private fun int(setting: IntSetting, value: Int?) = Value.IntValue(setting, value)
    private fun bool(setting: BooleanSetting, value: Boolean?) = Value.BoolValue(setting, value)

    // Numbers are the core's enums (common/settings_enums.h): ResolutionSetup Res1_2X = 0, Res3X = 5
    // (the highest offered under Graphics); ScalingFilter NearestNeighbor = 0, Bicubic = 2;
    // AntiAliasing None = 0, Smaa = 2; GpuAccuracy Normal = 0, High = 1; AnisotropyMode Default = 1,
    // X16 = 5; CpuAccuracy Unsafe = 2; VramUsageMode Conservative = 0, Aggressive = 1.
    private fun values(preset: Preset): List<Value> = when (preset) {
        Preset.SPEED -> listOf(
            int(IntSetting.RENDERER_RESOLUTION, 0),
            int(IntSetting.RENDERER_SCALING_FILTER, 0),
            int(IntSetting.RENDERER_ANTI_ALIASING, 0),
            int(IntSetting.RENDERER_ACCURACY, 0),
            int(IntSetting.MAX_ANISOTROPY, 1),
            int(IntSetting.CPU_ACCURACY, 2),
            int(IntSetting.VRAM_USAGE_MODE, 0),
            bool(BooleanSetting.USE_DOCKED_MODE, false),
            bool(BooleanSetting.RENDERER_ASYNCHRONOUS_SHADERS, true),
            bool(BooleanSetting.FAST_GPU_TIME, true)
        )
        Preset.NORMAL -> listOf(
            int(IntSetting.RENDERER_RESOLUTION, null),
            int(IntSetting.RENDERER_SCALING_FILTER, null),
            int(IntSetting.RENDERER_ANTI_ALIASING, null),
            int(IntSetting.RENDERER_ACCURACY, null),
            int(IntSetting.MAX_ANISOTROPY, null),
            int(IntSetting.CPU_ACCURACY, null),
            int(IntSetting.VRAM_USAGE_MODE, null),
            bool(BooleanSetting.USE_DOCKED_MODE, null),
            bool(BooleanSetting.RENDERER_ASYNCHRONOUS_SHADERS, null),
            bool(BooleanSetting.FAST_GPU_TIME, null)
        )
        Preset.QUALITY -> listOf(
            int(IntSetting.RENDERER_RESOLUTION, 5),
            int(IntSetting.RENDERER_SCALING_FILTER, 2),
            int(IntSetting.RENDERER_ANTI_ALIASING, 2),
            int(IntSetting.RENDERER_ACCURACY, 1),
            int(IntSetting.MAX_ANISOTROPY, 5),
            int(IntSetting.CPU_ACCURACY, null),
            int(IntSetting.VRAM_USAGE_MODE, 1),
            bool(BooleanSetting.USE_DOCKED_MODE, true),
            bool(BooleanSetting.RENDERER_ASYNCHRONOUS_SHADERS, false),
            bool(BooleanSetting.FAST_GPU_TIME, null)
        )
    }

    private val settings: List<AbstractSetting>
        get() = values(Preset.NORMAL).map {
            when (it) {
                is Value.IntValue -> it.setting
                is Value.BoolValue -> it.setting
            }
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

    /** The mode the loaded config's values match, or null (custom values). */
    fun current(): Preset? = Preset.entries.firstOrNull { preset ->
        values(preset).all {
            when (it) {
                is Value.IntValue -> it.setting.getInt() == (it.value ?: it.setting.defaultValue)
                is Value.BoolValue -> it.setting.getBoolean() == (it.value ?: it.setting.defaultValue)
            }
        }
    }

    /** The global mode, saved. */
    fun applyGlobal(preset: Preset) {
        apply(preset)
        NativeConfig.saveGlobalConfig()
    }

    /** The mode of [game]'s own config, or null if it uses the global values for them. */
    fun currentFor(game: Game): Preset? = withGameConfig(game, save = false) {
        if (settings.all { it.global }) null else current()
    }

    /** Gives [game] its own [preset], or with null the global values back. */
    fun applyFor(game: Game, preset: Preset?) {
        withGameConfig(game, save = true) {
            if (preset != null) {
                apply(preset)
            } else {
                settings.forEach { it.global = true }
            }
        }
    }

    private fun <T> withGameConfig(game: Game, save: Boolean, block: () -> T): T {
        SettingsFile.loadCustomConfig(game)
        try {
            return block().also { if (save) NativeConfig.savePerGameConfig() }
        } finally {
            NativeConfig.unloadPerGameConfig()
        }
    }
}
