// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import android.content.Context
import dev.ironlemon.ironlemon_emulator.R

object PlayTimeUtils {
    /** "2h 5m", "12m 40s" or "35s": the two largest units, as a play time badge shows them. */
    fun formatReadable(context: Context, totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val rest = seconds % 60
        val h = context.getString(R.string.hours_abbr)
        val m = context.getString(R.string.minutes_abbr)
        val s = context.getString(R.string.seconds_abbr)
        return when {
            hours > 0 -> "$hours$h $minutes$m"
            minutes > 0 -> "$minutes$m $rest$s"
            else -> "$rest$s"
        }
    }
}
