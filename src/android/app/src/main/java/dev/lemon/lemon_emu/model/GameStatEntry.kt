// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.model

data class GameStatEntry(
    val game: Game,
    val playTimeSeconds: Long,
    val lastPlayedMillis: Long,
    val sessionCount: Int
)
