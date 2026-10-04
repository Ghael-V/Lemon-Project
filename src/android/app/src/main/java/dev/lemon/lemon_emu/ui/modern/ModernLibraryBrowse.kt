// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.utils.GameStatsUtils

/**
 * How the library lays the games out. The carousel is the original single row (a big grid on a
 * portrait screen); the grid and the list show many games at once and scroll vertically, which is
 * what a very large library needs. The choice is saved in the preferences under [PREF].
 */
enum class LibraryView(val id: Int, val label: Int) {
    CAROUSEL(0, R.string.library_view_carousel),
    GRID(1, R.string.library_view_grid),
    LIST(2, R.string.library_view_list);

    companion object {
        const val PREF = "library_view_mode"

        fun fromId(id: Int): LibraryView = values().firstOrNull { it.id == id } ?: CAROUSEL
    }
}

/** Grid or list on a wide screen: the selected game on the left, many games scrolling on the right. */
@Composable
internal fun LandscapeBrowseLayout(
    view: LibraryView,
    visible: List<Game>,
    selected: Game?,
    search: String,
    searching: Boolean,
    showQLaunch: Boolean,
    loading: Boolean,
    actions: LibraryActions,
    onSearch: (String) -> Unit,
    onSearching: (Boolean) -> Unit,
    onSort: () -> Unit,
    onViews: () -> Unit,
    onSelect: (Game) -> Unit,
    onFocusSelect: (Game) -> Unit,
    onOpenSheet: (Game) -> Unit,
    onShowAll: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 36.dp, vertical = 12.dp)
    ) {
        TopBar(search, searching, onSearch, onSearching, onSort, onViews, actions)
        Spacer(Modifier.height(10.dp))
        if (visible.isEmpty()) {
            EmptyState(actions, loading)
            return@Column
        }
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SelectedPanel(selected, actions, onOpenSheet, Modifier.weight(0.34f).fillMaxHeight())
            BrowseContent(
                view, visible, selected, showQLaunch, actions,
                onSelect, onFocusSelect, onOpenSheet, onShowAll,
                tileMin = 112.dp, shortcutHeight = 56.dp, header = null,
                modifier = Modifier.weight(0.66f).fillMaxHeight()
            )
        }
    }
}

/** Grid or list on a portrait screen: the selected game's block first, then everything else. */
@Composable
internal fun PortraitBrowseLayout(
    view: LibraryView,
    visible: List<Game>,
    selected: Game?,
    search: String,
    searching: Boolean,
    showQLaunch: Boolean,
    loading: Boolean,
    actions: LibraryActions,
    onSearch: (String) -> Unit,
    onSearching: (Boolean) -> Unit,
    onSort: () -> Unit,
    onViews: () -> Unit,
    onSelect: (Game) -> Unit,
    onFocusSelect: (Game) -> Unit,
    onOpenSheet: (Game) -> Unit,
    onShowAll: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        TopBar(search, searching, onSearch, onSearching, onSort, onViews, actions)
        Spacer(Modifier.height(12.dp))
        if (visible.isEmpty()) {
            EmptyState(actions, loading)
            return@Column
        }
        val header: (@Composable () -> Unit)? = selected?.let { game ->
            { HeroInfo(game, actions, onMore = { onOpenSheet(game) }) }
        }
        BrowseContent(
            view, visible, selected, showQLaunch, actions,
            onSelect, onFocusSelect, onOpenSheet, onShowAll,
            tileMin = 100.dp, shortcutHeight = 84.dp, header = header,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun BrowseContent(
    view: LibraryView,
    visible: List<Game>,
    selected: Game?,
    showQLaunch: Boolean,
    actions: LibraryActions,
    onSelect: (Game) -> Unit,
    onFocusSelect: (Game) -> Unit,
    onOpenSheet: (Game) -> Unit,
    onShowAll: () -> Unit,
    tileMin: Dp,
    shortcutHeight: Dp,
    header: (@Composable () -> Unit)?,
    modifier: Modifier
) {
    if (view == LibraryView.LIST) {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (header != null) {
                item(key = "header") { header() }
            }
            items(visible, key = { it.path }) { game ->
                GameRow(
                    game = game,
                    selected = game.path == selected?.path,
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) onFocusSelect(game) },
                    onLongClick = { onOpenSheet(game) },
                    onClick = { onSelect(game) }
                )
            }
            item(key = "shortcuts") { ShortcutRow(actions, showQLaunch, onShowAll, shortcutHeight) }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(tileMin),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = modifier
        ) {
            if (header != null) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
            }
            items(visible, key = { it.path }) { game ->
                GameTile(
                    game = game,
                    size = Dp.Unspecified,
                    selected = game.path == selected?.path,
                    dimmed = game.path != selected?.path,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .onFocusChanged { if (it.hasFocus) onFocusSelect(game) },
                    onLongClick = { onOpenSheet(game) },
                    onClick = { onSelect(game) }
                )
            }
            item(key = "shortcuts", span = { GridItemSpan(maxLineSpan) }) {
                ShortcutRow(actions, showQLaunch, onShowAll, shortcutHeight)
            }
        }
    }
}

/** One row of the list view: the icon and the title, with the same select-then-launch behaviour. */
@Composable
private fun GameRow(
    game: Game,
    selected: Boolean,
    modifier: Modifier,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val icon = rememberGameIcon(game)
    Row(
        modifier
            .fillMaxWidth()
            .lemonInteractive(
                shape = shape,
                cornerRadius = 18.dp,
                selected = selected,
                focusScale = 1.02f,
                role = null,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .clip(shape)
            .background(
                if (selected) LemonColors.SurfaceRaised else LemonColors.Surface.copy(alpha = 0.85f)
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(LemonColors.Background)
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        BasicText(
            text = game.title,
            style = LemonType.Button.copy(fontSize = 16.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** The selected game beside a wide grid or list: its art, name, play time and the actions. */
@Composable
private fun SelectedPanel(
    game: Game?,
    actions: LibraryActions,
    onOpenSheet: (Game) -> Unit,
    modifier: Modifier
) {
    if (game == null) {
        Box(modifier)
        return
    }
    val context = LocalContext.current
    val icon = rememberGameIcon(game)
    val playtime = remember(game.path) { GameStatsUtils.buildAbbreviated(context, game) }
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .widthIn(max = 150.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(LemonColors.Surface)
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        BasicText(
            text = game.title,
            style = LemonType.Display.copy(fontSize = 22.sp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (playtime != null) {
            BasicText(text = playtime, style = LemonType.Body)
        }
        LemonButton(
            text = stringResource(R.string.play),
            iconRes = R.drawable.ic_play,
            primary = true,
            height = 46.dp,
            modifier = Modifier.fillMaxWidth()
        ) { actions.onLaunch(game) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LemonButton(
                text = stringResource(R.string.lemon_view_details),
                height = 46.dp,
                modifier = Modifier.weight(1f)
            ) { actions.onDetails(game) }
            LemonIconButton(
                R.drawable.ic_more_vert,
                stringResource(R.string.lemon_more_options),
                size = 46.dp
            ) { onOpenSheet(game) }
        }
    }
}
