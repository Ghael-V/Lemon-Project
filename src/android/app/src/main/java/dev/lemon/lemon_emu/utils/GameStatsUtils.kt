// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.utils

import android.content.Context
import android.text.format.DateUtils
import androidx.preference.PreferenceManager
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.model.Game

object GameStatsUtils {
    fun getPlayTimeSeconds(game: Game): Long = NativeLibrary.playTimeManagerGetPlayTime(game.programId)

    fun getLastPlayedMillis(context: Context, game: Game): Long =
        PreferenceManager.getDefaultSharedPreferences(context).getLong(game.keyLastPlayedTime, 0L)

    fun getSessionCount(context: Context, game: Game): Int =
        PreferenceManager.getDefaultSharedPreferences(context).getInt(game.keySessionCount, 0)

    /** The most recently played game in [games], or null if none of them have been played yet. */
    fun findLastPlayed(context: Context, games: List<Game>): Game? =
        games.maxByOrNull { getLastPlayedMillis(context, it) }
            ?.takeIf { getLastPlayedMillis(context, it) > 0L }

    /**
     * Formats a "playtime · last played · sessions" summary from already-known values (e.g.
     * a precomputed [dev.lemon.lemon_emu.model.GameStatEntry]), avoiding redundant native/prefs
     * reads when the caller already has this data on hand.
     */
    fun formatFullSummary(
        context: Context,
        playTimeSeconds: Long,
        lastPlayedMillis: Long,
        sessionCount: Int
    ): String {
        val readablePlayTime = PlayTimeUtils.formatReadable(context, playTimeSeconds)
        val lastPlayedRelative = DateUtils.getRelativeTimeSpanString(
            lastPlayedMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )
        val sessionsText = context.resources.getQuantityString(
            R.plurals.game_sessions_count,
            sessionCount,
            sessionCount
        )

        return "$readablePlayTime · $lastPlayedRelative · $sessionsText"
    }

    /**
     * Full "playtime · last played · sessions" summary for the carousel's centered-card
     * overlay. Returns null if the game has never been played, so the overlay can be skipped
     * entirely rather than showing an empty/placeholder badge.
     */
    fun buildFullSummary(context: Context, game: Game): String? {
        val lastPlayedMillis = getLastPlayedMillis(context, game)
        if (lastPlayedMillis <= 0L) return null
        return formatFullSummary(
            context,
            getPlayTimeSeconds(game),
            lastPlayedMillis,
            getSessionCount(context, game)
        )
    }

    /**
     * Short "playtime only" badge for compact card layouts (grid, list). Returns null if the
     * game has never been played, so callers can skip showing the badge entirely.
     */
    fun buildAbbreviated(context: Context, game: Game): String? {
        val playTimeSeconds = getPlayTimeSeconds(game)
        if (playTimeSeconds <= 0L) return null
        return PlayTimeUtils.formatReadable(context, playTimeSeconds)
    }
}
