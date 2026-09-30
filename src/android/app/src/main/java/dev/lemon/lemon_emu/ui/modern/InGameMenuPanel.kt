// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import android.view.Menu
import android.view.MenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.model.Game

/**
 * The in-game side panel. It is drawn from the classic menu (the same items, in the same order) and
 * every tap goes through that menu's own handler, so no option can be lost or behave differently;
 * only how it looks changes. [tick] makes it re-read the menu, whose titles and icons change while
 * playing (pause / resume, show / hide overlay, lock drawer).
 */
@Composable
fun InGameMenuPanel(
    menu: Menu,
    game: Game?,
    tick: Int,
    onItem: (Int) -> Unit
) {
    val context = LocalContext.current
    var localTick by remember { mutableIntStateOf(0) }
    val items = remember(tick, localTick) {
        (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun act(item: MenuItem) {
        onItem(item.itemId)
        localTick++
    }

    val pause = items.firstOrNull { it.itemId == R.id.menu_pause_emulation }
    val quickSave = items.firstOrNull { it.itemId == R.id.menu_quick_save_state }
    val quickLoad = items.firstOrNull { it.itemId == R.id.menu_quick_load_state }
    val exit = items.firstOrNull { it.itemId == R.id.menu_exit }
    val special = setOf(
        R.id.menu_pause_emulation,
        R.id.menu_quick_save_state,
        R.id.menu_quick_load_state,
        R.id.menu_exit
    )
    val rest = items.filter { it.itemId !in special }

    Column(
        Modifier
            .width(340.dp)
            .fillMaxHeight()
            .background(LemonColors.Background.copy(alpha = 0.97f))
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Header(game)
        Spacer(Modifier.height(4.dp))

        pause?.let {
            val resumes = it.title?.toString() == context.getString(R.string.emulation_unpause)
            LemonButton(
                text = it.title?.toString().orEmpty(),
                iconRes = if (resumes) R.drawable.ic_play else R.drawable.ic_pause,
                primary = true,
                height = 50.dp,
                modifier = Modifier.fillMaxWidth().focusRequester(first)
            ) { act(it) }
        }

        if (quickSave != null || quickLoad != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickSave?.let {
                    ShortcutTile(R.drawable.ic_save, it.title?.toString().orEmpty(), Modifier.weight(1f).height(72.dp)) { act(it) }
                }
                quickLoad?.let {
                    ShortcutTile(R.drawable.ic_restore, it.title?.toString().orEmpty(), Modifier.weight(1f).height(72.dp)) { act(it) }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(LemonColors.Outline))

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rest.forEach { item -> MenuRow(item) { act(item) } }
        }

        exit?.let {
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .lemonInteractive(
                        shape = shape,
                        cornerRadius = 16.dp,
                        glowColor = LemonColors.Red,
                        onClick = { act(it) }
                    )
                    .clip(shape)
                    .border(1.dp, LemonColors.Red.copy(alpha = 0.55f), shape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_exit),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(LemonColors.Red),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                BasicText(it.title?.toString().orEmpty(), style = LemonType.Button.copy(color = LemonColors.Red, fontSize = 15.sp))
            }
        }
    }
}

@Composable
private fun Header(game: Game?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val icon = game?.let { rememberGameIcon(it) }
        val shape = RoundedCornerShape(14.dp)
        Box(
            Modifier
                .size(48.dp)
                .clip(shape)
                .background(LemonColors.Surface)
        ) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.matchParentSize())
            }
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                text = game?.title.orEmpty(),
                style = LemonType.Heading.copy(fontSize = 18.sp),
                maxLines = 2
            )
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val icon = itemIcon(item)
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .lemonInteractive(shape = shape, cornerRadius = 16.dp, focusScale = 1.03f, onClick = onClick)
            .clip(shape)
            .background(LemonColors.SurfaceRaised.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, colorFilter = ColorFilter.tint(LemonColors.Lemon), modifier = Modifier.size(20.dp))
        } else {
            Spacer(Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        BasicText(item.title?.toString().orEmpty(), style = LemonType.Button.copy(fontSize = 15.sp), maxLines = 1)
    }
}

/** The item's own icon (some are set while playing), else one picked for its role. */
@Composable
private fun itemIcon(item: MenuItem): ImageBitmap? {
    val context = LocalContext.current
    item.icon?.let { return runCatching { it.toBitmap().asImageBitmap() }.getOrNull() }
    val res = when (item.itemId) {
        R.id.menu_lemon_cheater -> R.drawable.ic_code
        R.id.menu_lemon_macro -> R.drawable.ic_record
        R.id.menu_overlay_layout, R.id.menu_overlay_controls -> R.drawable.ic_overlay
        R.id.menu_settings, R.id.menu_settings_per_game -> R.drawable.ic_settings
        R.id.menu_quick_settings -> R.drawable.ic_options
        R.id.menu_controls -> R.drawable.ic_controller
        R.id.menu_multiplayer -> R.drawable.ic_multiplayer
        R.id.menu_load_amiibo -> R.drawable.ic_nfc
        else -> return null
    }
    return remember(res) {
        androidx.core.content.ContextCompat.getDrawable(context, res)
            ?.let { runCatching { it.toBitmap().asImageBitmap() }.getOrNull() }
    }
}
