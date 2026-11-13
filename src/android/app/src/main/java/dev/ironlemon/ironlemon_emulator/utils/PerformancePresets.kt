// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import androidx.annotation.StringRes
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.settings.model.IntSetting
import dev.ironlemon.ironlemon_emulator.features.settings.utils.SettingsFile
import dev.ironlemon.ironlemon_emulator.model.Game

/**
 * Battery, Balanced and Quality: tested combinations of graphics settings that can also be set one
 * by one under Graphics. A preset is not stored anywhere: it is the values it sets, and the active
 * one is the one whose values the settings hold. Applied globally, or to one game through its
 * custom config like any per-game setting.
 */
object PerformancePresets {
    enum class Preset(@StringRes val titleRes: Int, @StringRes val descriptionRes: Int) {
        BATTERY(R.string.preset_battery, R.string.preset_battery_description),
        BALANCED(R.string.preset_balanced, R.string.preset_balanced_description),
        QUALITY(R.string.preset_quality, R.string.preset_quality_description)
    }

    // Values are Yuzu's enums (common/settings_enums.h): ResolutionSetup Res1_2X = 0, Res1X = 2,
    // Res2X = 4; ScalingFilter Bilinear = 1, Bicubic = 2; AntiAliasing None = 0, Fxaa = 1;
    // GpuAccuracy Normal = 0, High = 1; VSyncMode Fifo = 2.
    private fun values(preset: Preset): Map<IntSetting, Int> = when (preset) {
        Preset.BATTERY -> mapOf(
            IntSetting.RENDERER_RESOLUTION to 0,
            IntSetting.RENDERER_SCALING_FILTER to 1,
            IntSetting.RENDERER_ANTI_ALIASING to 0,
            IntSetting.RENDERER_ACCURACY to 0,
            IntSetting.RENDERER_VSYNC to 2
        )
        Preset.BALANCED -> mapOf(
            IntSetting.RENDERER_RESOLUTION to 2,
            IntSetting.RENDERER_SCALING_FILTER to 1,
            IntSetting.RENDERER_ANTI_ALIASING to 0,
            IntSetting.RENDERER_ACCURACY to 0,
            IntSetting.RENDERER_VSYNC to 2
        )
        Preset.QUALITY -> mapOf(
            IntSetting.RENDERER_RESOLUTION to 4,
            IntSetting.RENDERER_SCALING_FILTER to 2,
            IntSetting.RENDERER_ANTI_ALIASING to 1,
            IntSetting.RENDERER_ACCURACY to 1,
            IntSetting.RENDERER_VSYNC to 2
        )
    }

    private val settings get() = values(Preset.BALANCED).keys

    /** Sets the preset's values in the config loaded now (the global one, or a game's). */
    fun apply(preset: Preset) {
        values(preset).forEach { (setting, value) -> setting.setInt(value) }
    }

    /** The preset the loaded config's values match, or null (custom values). */
    fun current(): Preset? = Preset.entries.firstOrNull { preset ->
        values(preset).all { (setting, value) -> setting.getInt() == value }
    }

    /** The global preset, saved. */
    fun applyGlobal(preset: Preset) {
        apply(preset)
        NativeConfig.saveGlobalConfig()
    }

    /** The preset of [game]'s own config, or null if it uses the global values for them. */
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
