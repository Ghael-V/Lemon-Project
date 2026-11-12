// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings

import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.settings.model.Settings.MenuTag

/** The top-level settings sections, as the settings search walks them. */
object SettingsCategories {
    class Category(
        val titleId: Int,
        val descriptionId: Int,
        val iconId: Int,
        val menuTag: MenuTag
    )

    val all = listOf(
        Category(
            R.string.preferences_system,
            R.string.preferences_system_description,
            R.drawable.ic_system_settings,
            MenuTag.SECTION_SYSTEM
        ),
        Category(
            R.string.preferences_graphics,
            R.string.preferences_graphics_description,
            R.drawable.ic_graphics,
            MenuTag.SECTION_RENDERER
        ),
        Category(
            R.string.preferences_audio,
            R.string.preferences_audio_description,
            R.drawable.ic_audio,
            MenuTag.SECTION_AUDIO
        ),
        Category(
            R.string.preferences_controls,
            R.string.preferences_controls_description,
            R.drawable.ic_controller,
            MenuTag.SECTION_INPUT
        ),
        Category(
            R.string.preferences_debug,
            R.string.preferences_debug_description,
            R.drawable.ic_code,
            MenuTag.SECTION_DEBUG
        )
    )
}
