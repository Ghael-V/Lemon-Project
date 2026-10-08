// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.utils.GameStatsUtils

/** Everything the library can ask the host fragment to do. */
class LibraryActions(
    val onLaunch: (Game) -> Unit,
    val onDetails: (Game) -> Unit,
    val onToggleFavorite: (Game) -> Unit,
    val onSettings: () -> Unit,
    val onStatistics: () -> Unit,
    val onAddGames: () -> Unit,
    val onManageFolders: () -> Unit,
    val onInstallContent: () -> Unit,
    val onLaunchQLaunch: () -> Unit,
    val onPerformance: (Game) -> Unit,
    val onDriverSettings: (Game) -> Unit,
    val onAddShortcut: (Game) -> Unit,
    val hasDriverOption: Boolean,
    val canPinShortcut: Boolean
)

private class SheetItem(val label: String, val selected: Boolean = false, val onClick: () -> Unit)

@Composable
fun ModernLibraryScreen(
    games: List<Game>,
    loading: Boolean,
    preferences: SharedPreferences,
    initialSelectedPath: String?,
    showQLaunch: Boolean,
    actions: LibraryActions
) {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filterId by remember { mutableIntStateOf(GameListFilters.savedFilter(preferences)) }
    var selectedPath by rememberSaveable { mutableStateOf(initialSelectedPath) }
    var favoritesVersion by remember { mutableIntStateOf(0) }
    var sheetGame by remember { mutableStateOf<Game?>(null) }
    var showSort by remember { mutableStateOf(false) }
    var showViews by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf(LibraryView.fromId(preferences.getInt(LibraryView.PREF, 0))) }

    val visible = remember(games, filterId, search, favoritesVersion) {
        GameListFilters.apply(preferences, games, filterId, search)
    }
    val selected = visible.firstOrNull { it.path == selectedPath } ?: visible.firstOrNull()

    val backdrop = selected?.let { rememberGameIcon(it) }

    fun select(game: Game) {
        if (game.path == selected?.path) actions.onLaunch(game) else selectedPath = game.path
    }

    Box(Modifier.fillMaxSize().background(LemonColors.Background)) {
        Crossfade(targetState = backdrop, animationSpec = tween(450), label = "backdrop") { bitmap ->
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(70.dp).graphicsLayer { alpha = 0.9f }
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(
                        LemonColors.Background.copy(alpha = 0.92f),
                        LemonColors.Background.copy(alpha = 0.55f),
                        LemonColors.Background.copy(alpha = 0.25f)
                    )
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, LemonColors.Background.copy(alpha = 0.9f)),
                    startY = 400f
                )
            )
        )

        if (viewMode != LibraryView.CAROUSEL && landscape) {
            LandscapeBrowseLayout(
                viewMode, visible, selected, search, searching, showQLaunch, loading, actions,
                onSearch = { search = it },
                onSearching = { searching = it; if (!it) search = "" },
                onSort = { showSort = true },
                onViews = { showViews = true },
                onSelect = ::select,
                onFocusSelect = { selectedPath = it.path },
                onOpenSheet = { sheetGame = it },
                onShowAll = { search = ""; searching = false; filterId = android.view.View.NO_ID }
            )
        } else if (viewMode != LibraryView.CAROUSEL) {
            PortraitBrowseLayout(
                viewMode, visible, selected, search, searching, showQLaunch, loading, actions,
                onSearch = { search = it },
                onSearching = { searching = it; if (!it) search = "" },
                onSort = { showSort = true },
                onViews = { showViews = true },
                onSelect = ::select,
                onFocusSelect = { selectedPath = it.path },
                onOpenSheet = { sheetGame = it },
                onShowAll = { search = ""; searching = false; filterId = android.view.View.NO_ID }
            )
        } else if (landscape) {
            LandscapeLayout(
                visible, selected, search, searching, showQLaunch, loading, actions,
                onSearch = { search = it },
                onSearching = { searching = it; if (!it) search = "" },
                onSort = { showSort = true },
                onViews = { showViews = true },
                onSelect = ::select,
                onFocusSelect = { selectedPath = it.path },
                onOpenSheet = { sheetGame = it },
                onShowAll = { search = ""; searching = false; filterId = android.view.View.NO_ID }
            )
        } else {
            PortraitLayout(
                visible, selected, search, searching, showQLaunch, loading, actions,
                onSearch = { search = it },
                onSearching = { searching = it; if (!it) search = "" },
                onSort = { showSort = true },
                onViews = { showViews = true },
                onSelect = ::select,
                onFocusSelect = { selectedPath = it.path },
                onOpenSheet = { sheetGame = it },
                onShowAll = { search = ""; searching = false; filterId = android.view.View.NO_ID }
            )
        }

        sheetGame?.let { game ->
            val isFavorite = preferences.getBoolean(game.keyIsFavorite, false)
            LemonSheet(
                title = game.title,
                items = listOf(
                    SheetItem(stringResource(R.string.play)) { actions.onLaunch(game) },
                    SheetItem(stringResource(R.string.per_game_settings)) { actions.onDetails(game) },
                    SheetItem(stringResource(R.string.performance_preset)) { actions.onPerformance(game) },
                    SheetItem(
                        stringResource(
                            if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites
                        )
                    ) {
                        actions.onToggleFavorite(game)
                        favoritesVersion++
                    }
                ) + listOfNotNull(
                    if (actions.hasDriverOption) {
                        SheetItem(stringResource(R.string.freedreno_per_game_title)) {
                            actions.onDriverSettings(game)
                        }
                    } else {
                        null
                    },
                    if (actions.canPinShortcut) {
                        SheetItem(stringResource(R.string.add_to_home_screen)) { actions.onAddShortcut(game) }
                    } else {
                        null
                    }
                ),
                onDismiss = { sheetGame = null }
            )
        }
        if (showViews) {
            LemonSheet(
                title = stringResource(R.string.library_view),
                items = LibraryView.values().map { view ->
                    SheetItem(stringResource(view.label), selected = viewMode == view) {
                        viewMode = view
                        preferences.edit().putInt(LibraryView.PREF, view.id).apply()
                    }
                },
                onDismiss = { showViews = false }
            )
        }
        if (showSort) {
            LemonSheet(
                title = stringResource(R.string.statistics_sort_by),
                items = GameListFilters.options.map { (id, label) ->
                    SheetItem(stringResource(label), selected = filterId == id) {
                        filterId = id
                        preferences.edit().putInt(GameListFilters.PREF_SORT_TYPE, id).apply()
                    }
                },
                onDismiss = { showSort = false }
            )
        }
    }
}

@Composable
private fun LandscapeLayout(
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
    val listState = rememberLazyListState()
    LaunchedEffect(selected?.path) {
        val index = visible.indexOfFirst { it.path == selected?.path }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 36.dp, vertical = 12.dp)
    ) {
        TopBar(search, searching, onSearch, onSearching, onSort, onViews, actions)
        Spacer(Modifier.height(10.dp))

        // The row of tiles takes whatever height is left after the title, buttons and shortcuts,
        // so the same screen fits a 1080p handheld (about 400 dp once the system bars are out) and
        // a tablet alike.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) {
                EmptyState(actions, loading)
            } else {
                val rowHeight = (maxHeight - 100.dp).coerceIn(112.dp, 214.dp)
                val selectedSize = rowHeight - 14.dp
                // Every tile takes the same slot; the selected one is only drawn bigger. That keeps
                // the scroll maths exact, so the selected tile always lands in the middle.
                val slot = selectedSize * 0.76f
                val selectedScale = selectedSize / slot
                val sidePadding = (maxWidth - slot) / 2
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LazyRow(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = sidePadding, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(30.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().height(rowHeight)
                    ) {
                        itemsIndexed(visible, key = { _, game -> game.path }) { _, game ->
                            val isSelected = game.path == selected?.path
                            val scale by animateFloatAsState(
                                targetValue = if (isSelected) selectedScale else 1f,
                                animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
                                label = "tileScale"
                            )
                            Box(
                                Modifier
                                    .size(slot)
                                    .zIndex(if (isSelected) 1f else 0f)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }
                            ) {
                                GameTile(
                                    game = game,
                                    size = slot,
                                    selected = isSelected,
                                    dimmed = !isSelected,
                                    modifier = Modifier.onFocusChanged { if (it.hasFocus) onFocusSelect(game) },
                                    onLongClick = { onOpenSheet(game) },
                                    onClick = { onSelect(game) }
                                )
                            }
                        }
                    }
                    selected?.let { HeroInfo(it, actions, centered = true, onMore = { onOpenSheet(it) }) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        ShortcutRow(actions, showQLaunch, onShowAll, tileHeight = 64.dp)
    }
}

@Composable
private fun PortraitLayout(
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            selected?.let {
                item(span = { GridItemSpan(2) }) { HeroInfo(it, actions, onMore = { onOpenSheet(it) }) }
            }
            items(visible, key = { it.path }) { game ->
                GameTile(
                    game = game,
                    size = 160.dp,
                    selected = game.path == selected?.path,
                    dimmed = game.path != selected?.path,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) onFocusSelect(game) },
                    onLongClick = { onOpenSheet(game) },
                    onClick = { onSelect(game) }
                )
            }
            item(span = { GridItemSpan(2) }) {
                ShortcutRow(actions, showQLaunch, onShowAll, tileHeight = 84.dp)
            }
        }
    }
}

@Composable
internal fun TopBar(
    search: String,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onSearching: (Boolean) -> Unit,
    onSort: () -> Unit,
    onViews: () -> Unit,
    actions: LibraryActions
) {
    val logo = @Composable {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(56.dp)
        )
    }
    val title = @Composable { modifier: Modifier ->
        BasicText(
            text = stringResource(R.string.lemon_library),
            style = LemonType.Display,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.padding(start = 4.dp)
        )
    }
    val buttons = @Composable {
        LemonIconButton(R.drawable.ic_search, stringResource(R.string.home_search_games)) {
            onSearching(true)
        }
        LemonIconButton(R.drawable.ic_view_grid, stringResource(R.string.library_view), onClick = onViews)
        LemonIconButton(R.drawable.ic_filter, stringResource(R.string.statistics_sort_by), onClick = onSort)
        LemonIconButton(R.drawable.ic_bar_chart, stringResource(R.string.statistics), onClick = actions.onStatistics)
        LemonIconButton(R.drawable.ic_settings, stringResource(R.string.preferences_settings), onClick = actions.onSettings)
    }
    val searchBar = @Composable { modifier: Modifier ->
        SearchField(search, onSearch, modifier)
        LemonIconButton(R.drawable.ic_clear, stringResource(android.R.string.cancel)) {
            onSearching(false)
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 600.dp) {
            // Phones in portrait: the title gets its own line, the buttons go underneath.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    logo()
                    title(Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth().height(48.dp),
                    horizontalArrangement = if (searching) Arrangement.Start else Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (searching) {
                        searchBar(Modifier.weight(1f).padding(end = 8.dp))
                    } else {
                        buttons()
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                logo()
                if (searching) {
                    searchBar(Modifier.weight(1f).padding(horizontal = 8.dp))
                } else {
                    title(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(LemonColors.SurfaceRaised.copy(alpha = 0.8f))
            .border(1.dp, LemonColors.Lemon.copy(alpha = 0.6f), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            colorFilter = ColorFilter.tint(LemonColors.TextMuted),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = LemonType.Button.copy(fontSize = 15.sp),
            cursorBrush = SolidColor(LemonColors.Lemon),
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        BasicText(stringResource(R.string.home_search_games), style = LemonType.Body)
                    }
                    inner()
                }
            }
        )
    }
}

@Composable
internal fun HeroInfo(game: Game, actions: LibraryActions, centered: Boolean = false, onMore: () -> Unit) {
    val context = LocalContext.current
    val playtime = remember(game.path) { GameStatsUtils.buildAbbreviated(context, game) }
    val arrangement = if (centered) Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = arrangement) {
            BasicText(
                text = game.title,
                style = LemonType.Display,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (playtime != null) {
                BasicText(
                    text = playtime,
                    style = LemonType.Body,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(LemonColors.Background.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        Row(horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
            LemonButton(
                text = stringResource(R.string.play),
                iconRes = R.drawable.ic_play,
                primary = true,
                height = 46.dp,
                modifier = Modifier.width(180.dp)
            ) { actions.onLaunch(game) }
            LemonButton(text = stringResource(R.string.lemon_view_details), height = 46.dp) {
                actions.onDetails(game)
            }
            LemonIconButton(
                R.drawable.ic_more_vert,
                stringResource(R.string.lemon_more_options),
                size = 46.dp,
                onClick = onMore
            )
        }
    }
}

@Composable
internal fun ShortcutRow(actions: LibraryActions, showQLaunch: Boolean, onShowAll: () -> Unit, tileHeight: Dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val modifier = Modifier.weight(1f).height(tileHeight)
        ShortcutTile(R.drawable.ic_home, stringResource(R.string.lemon_all_games), modifier, onShowAll)
        ShortcutTile(R.drawable.ic_folder_open, stringResource(R.string.lemon_game_folders), modifier, actions.onManageFolders)
        ShortcutTile(R.drawable.ic_install, stringResource(R.string.lemon_install_content), modifier, actions.onInstallContent)
        ShortcutTile(R.drawable.ic_add, stringResource(R.string.add_games), modifier, actions.onAddGames)
        if (showQLaunch) {
            ShortcutTile(R.drawable.ic_controller, stringResource(R.string.qlaunch_applet), modifier, actions.onLaunchQLaunch)
        }
    }
}

@Composable
internal fun EmptyState(actions: LibraryActions, loading: Boolean) {
    if (loading) {
        Box(Modifier.fillMaxSize())
        return
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText(
            text = stringResource(R.string.empty_gamelist),
            style = LemonType.Body.copy(fontSize = 16.sp)
        )
        Spacer(Modifier.height(18.dp))
        LemonButton(
            text = stringResource(R.string.add_games),
            iconRes = R.drawable.ic_add,
            primary = true,
            onClick = actions.onAddGames
        )
    }
}

/** A centered card of choices, used for a game's options and for sorting. */
@Composable
private fun LemonSheet(title: String, items: List<SheetItem>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        val shape = RoundedCornerShape(26.dp)
        Column(
            Modifier
                .width(340.dp)
                .clip(shape)
                .background(LemonColors.Surface)
                .border(1.dp, LemonColors.Outline, shape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicText(title, style = LemonType.Heading, maxLines = 2, modifier = Modifier.padding(bottom = 6.dp))
            items.forEachIndexed { index, item ->
                val itemShape = RoundedCornerShape(16.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                        .lemonInteractive(
                            shape = itemShape,
                            cornerRadius = 16.dp,
                            selected = item.selected,
                            focusScale = 1.03f,
                            glowColor = LemonColors.Lemon,
                            onClick = { item.onClick(); onDismiss() }
                        )
                        .clip(itemShape)
                        .background(LemonColors.SurfaceRaised)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(item.label, style = LemonType.Button)
                }
            }
        }
    }
}
