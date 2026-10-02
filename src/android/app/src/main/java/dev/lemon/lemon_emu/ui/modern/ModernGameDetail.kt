// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import android.content.res.Configuration
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.model.GameProperty
import dev.lemon.lemon_emu.model.InstallableProperty
import dev.lemon.lemon_emu.model.SubmenuProperty

/** The tabs of a game's page; each property from the classic page lands in one of them. */
private enum class DetailTab(val titleRes: Int) {
    Extras(R.string.add_ons),
    Settings(R.string.preferences_settings),
    Data(R.string.lemon_tab_data)
}

private fun tabOf(property: GameProperty): DetailTab = when (property.titleId) {
    R.string.add_ons -> DetailTab.Extras
    R.string.save_data,
    R.string.delete_save_data,
    R.string.clear_shader_cache,
    R.string.reset_playtime -> DetailTab.Data
    else -> DetailTab.Settings
}

class GameDetailActions(
    val onBack: () -> Unit,
    val onPlay: () -> Unit,
    val onShortcut: () -> Unit,
    val onEditPlaytime: () -> Unit
)

/** A game's page: its art and stats on one side, its options in tabs on the other. */
@Composable
fun ModernGameDetail(
    game: Game,
    playtime: String,
    usage: String,
    properties: List<GameProperty>,
    playEnabled: Boolean,
    canPinShortcut: Boolean,
    actions: GameDetailActions
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val icon = rememberGameIcon(game)
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(LemonColors.Background)) {
        Crossfade(targetState = icon, animationSpec = tween(400), label = "detailBackdrop") { bitmap ->
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(80.dp).graphicsLayer { alpha = 0.55f }
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(
                        LemonColors.Background.copy(alpha = 0.55f),
                        LemonColors.Background.copy(alpha = 0.92f)
                    )
                )
            )
        )

        val side = @Composable {
            SidePanel(game, icon, playtime, usage, playEnabled, canPinShortcut, actions)
        }
        val content = @Composable { modifier: Modifier ->
            Column(modifier) {
                Tabs(tab) { tab = it }
                Spacer(Modifier.height(12.dp))
                val shown = properties.filter { tabOf(it).ordinal == tab }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(shown, key = { it.titleId }) { PropertyCard(it) }
                }
            }
        }

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.width(260.dp).fillMaxHeight()) { side() }
                    content(Modifier.weight(1f).padding(top = 48.dp))
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp)) {
                    side()
                    Spacer(Modifier.height(12.dp))
                    content(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SidePanel(
    game: Game,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    playtime: String,
    usage: String,
    playEnabled: Boolean,
    canPinShortcut: Boolean,
    actions: GameDetailActions
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LemonIconButton(R.drawable.ic_back, stringResource(R.string.back), onClick = actions.onBack)
        Spacer(Modifier.weight(1f))
        if (canPinShortcut) {
            LemonIconButton(R.drawable.ic_shortcut, stringResource(R.string.add_to_home_screen), onClick = actions.onShortcut)
        }
    }
    Spacer(Modifier.height(if (landscape) 14.dp else 8.dp))
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier
            // The cover takes what the screen has left after the title, stats and Play button.
            .then(
                if (landscape) {
                    Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp - 330).coerceIn(90, 220).dp)
                } else {
                    Modifier.size(150.dp)
                }
            )
            .clip(shape)
            .background(LemonColors.Surface)
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
    Spacer(Modifier.height(12.dp))
    BasicText(game.title, style = LemonType.Display.copy(fontSize = 24.sp), maxLines = 3)
    Spacer(Modifier.height(4.dp))
    BasicText(
        playtime,
        style = LemonType.Body.copy(fontSize = 14.sp, color = LemonColors.Text),
        modifier = Modifier.clickable(onClick = actions.onEditPlaytime)
    )
    BasicText(usage, style = LemonType.Body.copy(fontSize = 13.sp), maxLines = 2)
    Spacer(Modifier.height(14.dp))
    LemonButton(
        text = stringResource(R.string.start),
        iconRes = R.drawable.ic_play,
        primary = true,
        height = 50.dp,
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (playEnabled) 1f else 0.4f },
        onClick = { if (playEnabled) actions.onPlay() }
    )
}

@Composable
private fun Tabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailTab.entries.forEachIndexed { index, item ->
            val isSelected = index == selected
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .height(38.dp)
                    .lemonInteractive(shape = shape, cornerRadius = 19.dp, focusScale = 1.04f, onClick = { onSelect(index) })
                    .clip(shape)
                    .background(if (isSelected) LemonColors.Lemon else LemonColors.SurfaceRaised.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    stringResource(item.titleRes),
                    style = LemonType.Button.copy(
                        fontSize = 14.sp,
                        color = if (isSelected) LemonColors.OnLemon else LemonColors.Text
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PropertyCard(property: GameProperty) {
    val shape = RoundedCornerShape(20.dp)
    val submenu = property as? SubmenuProperty
    val installable = property as? InstallableProperty
    val flowDetails = submenu?.detailsFlow?.collectAsState()?.value
    val details = flowDetails ?: submenu?.details?.invoke()
    val onClick: (() -> Unit)? = submenu?.action

    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.lemonInteractive(shape = shape, cornerRadius = 20.dp, focusScale = 1.02f, pressScale = 0.97f, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(LemonColors.Surface.copy(alpha = 0.9f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Image(
            painter = painterResource(property.iconId),
            contentDescription = null,
            colorFilter = ColorFilter.tint(LemonColors.Lemon),
            modifier = Modifier.size(24.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(stringResource(property.titleId), style = LemonType.Button.copy(fontSize = 15.sp))
            BasicText(stringResource(property.descriptionId), style = LemonType.Body.copy(fontSize = 13.sp), maxLines = 3)
            if (!details.isNullOrEmpty()) {
                BasicText(details, style = LemonType.Label.copy(letterSpacing = 0.sp, fontSize = 12.sp), maxLines = 1)
            }
        }
        submenu?.secondaryActions?.filter { it.isShown }?.forEach { secondary ->
            LemonIconButton(secondary.iconId, stringResource(secondary.descriptionId), size = 40.dp, onClick = secondary.action)
        }
        installable?.install?.let {
            LemonIconButton(R.drawable.ic_import, stringResource(R.string.install), size = 40.dp, onClick = it)
        }
        installable?.export?.let {
            LemonIconButton(R.drawable.ic_export, stringResource(R.string.export), size = 40.dp, onClick = it)
        }
    }
}
