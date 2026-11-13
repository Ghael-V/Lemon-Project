// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.overlay.model

import dev.ironlemon.ironlemon_emulator.R

/** One-tap starting points for the on-screen controller, all fully editable afterwards. */
enum class OverlayPreset(val titleRes: Int) {
    Default(R.string.overlay_preset_default),
    Big(R.string.overlay_preset_big),
    Swapped(R.string.overlay_preset_swapped)
}
