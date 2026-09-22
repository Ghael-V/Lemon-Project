// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.overlay.model

import dev.lemon.lemon_emu.R

enum class OverlayPreset(val titleRes: Int) {
    Default(R.string.overlay_preset_default),
    Big(R.string.overlay_preset_big),
    Swapped(R.string.overlay_preset_swapped)
}
