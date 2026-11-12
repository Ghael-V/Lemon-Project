// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dev.ironlemon.ironlemon_emulator.model.Game

/**
 * Play statistics kept per game in the shared preferences: total play time, the last time it was
 * started and how many times. The core has no play time tracking, so the emulation screen reports
 * the time it spends in the foreground running a game ([startSession] / [endSession]).
 */
object GameStatsUtils {
    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun getPlayTimeSeconds(context: Context, game: Game): Long =
        prefs(context).getLong(game.keyPlayTimeSeconds, 0L)

    fun getLastPlayedMillis(context: Context, game: Game): Long =
        prefs(context).getLong(game.keyLastPlayedTime, 0L)

    fun getSessionCount(context: Context, game: Game): Int =
        prefs(context).getInt(game.keySessionCount, 0)

    /** The most recently played game in [games], or null if none of them have been played yet. */
    fun findLastPlayed(context: Context, games: List<Game>): Game? =
        games.maxByOrNull { getLastPlayedMillis(context, it) }
            ?.takeIf { getLastPlayedMillis(context, it) > 0L }

    /** Play time for a compact badge, or null if the game hasn't been played yet. */
    fun buildAbbreviated(context: Context, game: Game): String? {
        val seconds = getPlayTimeSeconds(context, game)
        if (seconds <= 0L) return null
        return PlayTimeUtils.formatReadable(context, seconds)
    }

    /** A game is being started: remember when, and count the session. */
    fun recordLaunch(context: Context, game: Game) {
        val preferences = prefs(context)
        preferences.edit {
            putLong(game.keyLastPlayedTime, System.currentTimeMillis())
            putInt(game.keySessionCount, preferences.getInt(game.keySessionCount, 0) + 1)
        }
    }

    private var sessionGame: Game? = null
    private var sessionStart = 0L

    /** The emulation screen is in the foreground running [game]. */
    fun startSession(game: Game) {
        sessionGame = game
        sessionStart = SystemClock.elapsedRealtime()
    }

    /** The emulation screen left the foreground: add the time since [startSession]. */
    fun endSession(context: Context) {
        val game = sessionGame ?: return
        sessionGame = null
        val seconds = (SystemClock.elapsedRealtime() - sessionStart) / 1000
        if (seconds <= 0) return
        val preferences = prefs(context)
        preferences.edit {
            putLong(game.keyPlayTimeSeconds, preferences.getLong(game.keyPlayTimeSeconds, 0L) + seconds)
        }
    }
}
