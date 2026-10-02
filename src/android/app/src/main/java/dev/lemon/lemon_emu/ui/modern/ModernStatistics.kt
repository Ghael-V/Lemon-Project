// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.model.GameStatEntry
import dev.lemon.lemon_emu.utils.GameStatsUtils
import dev.lemon.lemon_emu.utils.PlayTimeUtils

class StatisticsActions(
    val onBack: () -> Unit,
    val onOpenGame: (GameStatEntry) -> Unit
)

private enum class StatSort(val labelRes: Int) {
    PLAYTIME(R.string.statistics_sort_playtime),
    LAST_PLAYED(R.string.statistics_sort_last_played),
    SESSIONS(R.string.statistics_sort_sessions)
}

/** The Statistics screen: library totals, a sort switch and every game ranked, with a playtime bar. */
@Composable
fun ModernStatistics(entries: List<GameStatEntry>, actions: StatisticsActions) {
    val context = LocalContext.current
    var sortIndex by remember { mutableIntStateOf(0) }
    val sort = StatSort.entries[sortIndex]
    val sorted = remember(entries, sort) {
        when (sort) {
            StatSort.PLAYTIME -> entries.sortedByDescending { it.playTimeSeconds }
            StatSort.LAST_PLAYED -> entries.sortedByDescending { it.lastPlayedMillis }
            StatSort.SESSIONS -> entries.sortedByDescending { it.sessionCount }
        }
    }
    val totalSeconds = remember(entries) { entries.sumOf { it.playTimeSeconds } }
    val totalSessions = remember(entries) { entries.sumOf { it.sessionCount } }
    val longest = remember(entries) { (entries.maxOfOrNull { it.playTimeSeconds } ?: 0L).coerceAtLeast(1L) }

    Box(
        Modifier
            .fillMaxSize()
            .background(LemonColors.Background)
            .background(
                Brush.radialGradient(
                    listOf(LemonColors.Lemon.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(1000f, 0f),
                    radius = 900f
                )
            )
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LemonIconButton(R.drawable.ic_back, stringResource(R.string.back), onClick = actions.onBack)
                BasicText(stringResource(R.string.statistics), style = LemonType.Display)
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard(
                            Modifier.weight(1f),
                            stringResource(R.string.lemon_stat_games),
                            entries.size.toString()
                        )
                        StatCard(
                            Modifier.weight(1.4f),
                            stringResource(R.string.lemon_stat_playtime),
                            PlayTimeUtils.formatReadable(context, totalSeconds),
                            highlight = true
                        )
                        StatCard(
                            Modifier.weight(1f),
                            stringResource(R.string.lemon_stat_sessions),
                            totalSessions.toString()
                        )
                    }
                }
                item {
                    Row(
                        Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BasicText(stringResource(R.string.statistics_sort_by).uppercase(), style = LemonType.Label)
                        Spacer(Modifier.width(4.dp))
                        StatSort.entries.forEachIndexed { index, option ->
                            SortChip(stringResource(option.labelRes), index == sortIndex) { sortIndex = index }
                        }
                    }
                }
                itemsIndexed(sorted, key = { _, entry -> entry.game.path }) { index, entry ->
                    StatRow(index + 1, entry, longest) { actions.onOpenGame(entry) }
                }
            }
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, label: String, value: String, highlight: Boolean = false) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (highlight) LemonColors.Lemon else LemonColors.Surface)
            .then(
                if (highlight) Modifier else Modifier.border(1.dp, LemonColors.Outline.copy(alpha = 0.6f), shape)
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        BasicText(
            label.uppercase(),
            style = LemonType.Label.copy(color = if (highlight) LemonColors.OnLemon.copy(alpha = 0.7f) else LemonColors.Lemon)
        )
        BasicText(
            value,
            style = LemonType.Display.copy(
                fontSize = 24.sp,
                color = if (highlight) LemonColors.OnLemon else LemonColors.Text
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SortChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .height(38.dp)
            .lemonInteractive(shape = shape, cornerRadius = 19.dp, selected = selected, focusScale = 1.06f, onClick = onClick)
            .clip(shape)
            .background(if (selected) LemonColors.Lemon.copy(alpha = 0.16f) else LemonColors.SurfaceRaised.copy(alpha = 0.7f))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text,
            style = LemonType.Button.copy(
                fontSize = 14.sp,
                color = if (selected) LemonColors.Lemon else LemonColors.Text
            )
        )
    }
}

@Composable
private fun StatRow(rank: Int, entry: GameStatEntry, longest: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(20.dp)
    val icon = rememberGameIcon(entry.game)
    val fraction = (entry.playTimeSeconds.toFloat() / longest).coerceIn(0f, 1f)
    val neverPlayed = stringResource(R.string.game_never_played)
    val summary = remember(entry) {
        if (entry.lastPlayedMillis <= 0L) {
            neverPlayed
        } else {
            GameStatsUtils.formatFullSummary(
                context,
                entry.playTimeSeconds,
                entry.lastPlayedMillis,
                entry.sessionCount
            )
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .lemonInteractive(shape = shape, cornerRadius = 20.dp, focusScale = 1.02f, pressScale = 0.98f, onClick = onClick)
            .clip(shape)
            .background(LemonColors.Surface)
            .border(1.dp, LemonColors.Outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BasicText(
            rank.toString(),
            style = LemonType.Heading.copy(
                fontSize = 18.sp,
                color = if (rank <= 3) LemonColors.Lemon else LemonColors.TextMuted
            ),
            modifier = Modifier.width(28.dp)
        )
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(LemonColors.SurfaceRaised)) {
            icon?.let {
                Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(
                entry.game.title,
                style = LemonType.Button.copy(fontSize = 16.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                summary,
                style = LemonType.Body.copy(fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Playtime relative to the most played game.
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(LemonColors.SurfaceRaised)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(LemonColors.Lemon)
                )
            }
        }
    }
}
