// SPDX-FileCopyrightText: 2024 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.lemon.lemon_emu.model

interface SelectableItem {
    var selected: Boolean
    fun onSelectionStateChanged(selected: Boolean)
}
