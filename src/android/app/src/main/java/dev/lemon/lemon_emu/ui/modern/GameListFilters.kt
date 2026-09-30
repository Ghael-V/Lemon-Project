// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import android.content.SharedPreferences
import android.view.View
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.model.Game
import info.debatty.java.stringsimilarity.Jaccard
import info.debatty.java.stringsimilarity.JaroWinkler
import java.util.Locale

/**
 * Sorting, filtering and fuzzy search of the library. The same rules as the classic library, and
 * the same saved sort choice (its key and menu ids), so switching interfaces keeps the sort.
 */
object GameListFilters {
    const val PREF_SORT_TYPE = "GamesSortType"

    val options: List<Pair<Int, Int>> = listOf(
        R.id.alphabetical to R.string.alphabetical,
        R.id.filter_recently_played to R.string.search_recently_played,
        R.id.filter_recently_added to R.string.search_recently_added,
        R.id.filter_favorites to R.string.filter_favorites
    )

    fun savedFilter(preferences: SharedPreferences): Int =
        preferences.getInt(PREF_SORT_TYPE, View.NO_ID)

    fun apply(
        preferences: SharedPreferences,
        base: List<Game>,
        filterId: Int,
        search: String
    ): List<Game> {
        val day = 24 * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        val filtered: List<Game> = when (filterId) {
            R.id.alphabetical -> base.sortedBy { it.title }
            R.id.filter_recently_played -> base
                .filter { preferences.getLong(it.keyLastPlayedTime, 0L) > now - day }
                .sortedByDescending { preferences.getLong(it.keyLastPlayedTime, 0L) }
            R.id.filter_recently_added -> base
                .filter { preferences.getLong(it.keyAddedToLibraryTime, 0L) > now - day }
                .sortedByDescending { preferences.getLong(it.keyAddedToLibraryTime, 0L) }
            R.id.filter_favorites -> base
                .filter { preferences.getBoolean(it.keyIsFavorite, false) }
                .sortedBy { it.title }
            else -> base
        }

        val term = search.trim().lowercase(Locale.getDefault())
        if (term.isEmpty()) {
            return filtered
        }
        val algorithm = if (term.length > 1) Jaccard(2) else JaroWinkler()
        return filtered
            .map { it to algorithm.similarity(term, it.title.lowercase(Locale.getDefault())) }
            .filter { it.second > 0.03 }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
