// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.activityViewModels
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.model.GamesViewModel
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonColors
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonPage
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonType
import dev.ironlemon.ironlemon_emulator.ui.modern.lemonInteractive
import dev.ironlemon.ironlemon_emulator.ui.modern.rememberGameIcon
import dev.ironlemon.ironlemon_emulator.utils.GameStatsUtils
import dev.ironlemon.ironlemon_emulator.utils.PlayTimeUtils

/**
 * How much each game has been played: totals for the library, then every game played, ranked by
 * play time, last played or sessions, with a bar for its share of the most played one.
 */
class StatisticsFragment : IronLemonComposeFragment() {
    private val gamesViewModel: GamesViewModel by activityViewModels()

    private enum class Sort(val titleRes: Int) {
        PLAYTIME(R.string.statistics_sort_playtime),
        LAST_PLAYED(R.string.statistics_sort_last_played),
        SESSIONS(R.string.statistics_sort_sessions)
    }

    private class Entry(val game: Game, val seconds: Long, val lastPlayed: Long, val sessions: Int)

    @Composable
    override fun ScreenContent() {
        val context = LocalContext.current
        val games by gamesViewModel.games.collectAsState()
        var sort by rememberSaveable { mutableStateOf(Sort.PLAYTIME) }
        val entries = remember(games) {
            games.map {
                Entry(
                    it,
                    GameStatsUtils.getPlayTimeSeconds(context, it),
                    GameStatsUtils.getLastPlayedMillis(context, it),
                    GameStatsUtils.getSessionCount(context, it)
                )
            }.filter { it.seconds > 0 || it.sessions > 0 }
        }
        val sorted = when (sort) {
            Sort.PLAYTIME -> entries.sortedByDescending { it.seconds }
            Sort.LAST_PLAYED -> entries.sortedByDescending { it.lastPlayed }
            Sort.SESSIONS -> entries.sortedByDescending { it.sessions }
        }
        val mostSeconds = entries.maxOfOrNull { it.seconds }?.coerceAtLeast(1) ?: 1

        IronLemonPage(title = stringResource(R.string.statistics), onBack = ::goBack) {
            LazyColumn(
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Total(
                            stringResource(R.string.statistics_total_playtime),
                            PlayTimeUtils.formatReadable(context, entries.sumOf { it.seconds }),
                            Modifier.weight(1f)
                        )
                        Total(
                            stringResource(R.string.statistics_games_played),
                            entries.size.toString(),
                            Modifier.weight(1f)
                        )
                        Total(
                            stringResource(R.string.statistics_sort_sessions),
                            entries.sumOf { it.sessions }.toString(),
                            Modifier.weight(1f)
                        )
                    }
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        BasicText(stringResource(R.string.statistics_sort_by), style = IronLemonType.Label)
                        Sort.entries.forEach { option ->
                            SortChip(stringResource(option.titleRes), option == sort) { sort = option }
                        }
                    }
                }
                if (sorted.isEmpty()) {
                    item {
                        BasicText(
                            stringResource(R.string.statistics_empty),
                            style = IronLemonType.Body,
                            modifier = Modifier.padding(top = 24.dp)
                        )
                    }
                }
                items(sorted, key = { it.game.path }) { entry ->
                    GameRow(entry, entry.seconds.toFloat() / mostSeconds)
                }
            }
        }
    }

    @Composable
    private fun Total(label: String, value: String, modifier: Modifier) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            modifier
                .clip(shape)
                .background(IronLemonColors.Surface)
                .padding(18.dp)
        ) {
            BasicText(value, style = IronLemonType.Display.copy(fontSize = 24.sp), maxLines = 1)
            BasicText(label, style = IronLemonType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun SortChip(text: String, selected: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier
                .lemonInteractive(shape = shape, cornerRadius = 16.dp, selected = selected, onClick = onClick)
                .clip(shape)
                .then(
                    if (selected) {
                        Modifier.background(IronLemonColors.Gradient)
                    } else {
                        Modifier.background(IronLemonColors.SurfaceRaised)
                    }
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            BasicText(
                text,
                style = IronLemonType.Button.copy(
                    fontSize = 14.sp,
                    color = if (selected) IronLemonColors.OnLemon else IronLemonColors.Text
                )
            )
        }
    }

    @Composable
    private fun GameRow(entry: Entry, share: Float) {
        val context = LocalContext.current
        val shape = RoundedCornerShape(18.dp)
        val icon = rememberGameIcon(entry.game)
        Row(
            Modifier
                .fillMaxWidth()
                .lemonInteractive(shape = shape, cornerRadius = 18.dp, focusScale = 1.02f, onClick = {})
                .clip(shape)
                .background(IronLemonColors.Surface)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                    .background(IronLemonColors.SurfaceRaised)
            ) {
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null, modifier = Modifier.matchParentSize())
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                BasicText(
                    entry.game.title,
                    style = IronLemonType.Button,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val last = if (entry.lastPlayed > 0) {
                    DateUtils.getRelativeTimeSpanString(
                        entry.lastPlayed,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString().replaceFirstChar { it.lowercase() }
                } else {
                    "-"
                }
                BasicText(
                    stringResource(
                        R.string.ironlemon_usage,
                        pluralStringResource(R.plurals.ironlemon_sessions, entry.sessions, entry.sessions),
                        last
                    ),
                    style = IronLemonType.Body.copy(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(IronLemonColors.SurfaceRaised)
                ) {
                    Box(
                        Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).height(6.dp)
                            .background(IronLemonColors.Gradient)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            BasicText(
                if (entry.seconds > 0) PlayTimeUtils.formatReadable(context, entry.seconds) else "-",
                style = IronLemonType.Button.copy(color = IronLemonColors.Lemon)
            )
        }
    }
}
