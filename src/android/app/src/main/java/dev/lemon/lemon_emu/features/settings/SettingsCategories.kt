// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.features.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.features.settings.model.Settings.MenuTag

/**
 * The top level of the settings: one entry per category, each opening its section. The settings
 * home screen and the settings root (also used for per-game settings) both list these, so every
 * option has a single home.
 */
data class SettingsCategory(
    @StringRes val titleId: Int,
    @StringRes val descriptionId: Int,
    @DrawableRes val iconId: Int,
    val menuTag: MenuTag,
    /** Whether the category has options that can be set per game. */
    val perGame: Boolean,
    /** Whether anything in it can be used while a game runs (opened from the in-game menu). */
    val inGame: Boolean = true
)

object SettingsCategories {
    val all = listOf(
        SettingsCategory(
            R.string.settings_lemon,
            R.string.settings_lemon_description,
            R.drawable.ic_palette,
            MenuTag.SECTION_APP_SETTINGS,
            perGame = false
        ),
        SettingsCategory(
            R.string.preferences_graphics,
            R.string.settings_graphics_description,
            R.drawable.ic_graphics,
            MenuTag.SECTION_RENDERER,
            perGame = true
        ),
        SettingsCategory(
            R.string.settings_performance,
            R.string.settings_performance_description,
            R.drawable.ic_frames,
            MenuTag.SECTION_PERFORMANCE,
            perGame = true
        ),
        SettingsCategory(
            R.string.preferences_controls,
            R.string.settings_controls_description,
            R.drawable.ic_controller,
            MenuTag.SECTION_INPUT,
            perGame = true
        ),
        SettingsCategory(
            R.string.settings_ingame_display,
            R.string.settings_ingame_display_description,
            R.drawable.ic_overlay,
            MenuTag.SECTION_INGAME_DISPLAY,
            // Overlays, screen layout and picture-in-picture are global options.
            perGame = false
        ),
        SettingsCategory(
            R.string.settings_console,
            R.string.settings_console_description,
            R.drawable.ic_system_settings,
            MenuTag.SECTION_SYSTEM,
            perGame = true
        ),
        SettingsCategory(
            R.string.settings_online,
            R.string.settings_online_description,
            R.drawable.ic_network,
            MenuTag.SECTION_ONLINE,
            perGame = true
        ),
        SettingsCategory(
            R.string.settings_content,
            R.string.settings_content_description,
            R.drawable.ic_folder_open,
            MenuTag.SECTION_CONTENT,
            perGame = false,
            // Folders, data, verification and applets all need the emulation stopped.
            inGame = false
        ),
        SettingsCategory(
            R.string.settings_help,
            R.string.settings_help_description,
            R.drawable.ic_info_outline,
            MenuTag.SECTION_HELP,
            perGame = false
        ),
        SettingsCategory(
            R.string.settings_advanced_debug,
            R.string.settings_advanced_debug_description,
            R.drawable.ic_code,
            MenuTag.SECTION_DEBUG,
            perGame = true
        )
    )
}
