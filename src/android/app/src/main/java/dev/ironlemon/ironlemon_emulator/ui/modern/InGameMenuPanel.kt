// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project / Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.ui.modern

import kotlinx.coroutines.delay
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.Game

/** What the floating performance card shows and does; every value is read fresh when the menu opens. */
class PerformanceInfo(
    val presetLabels: List<String>,
    val activePreset: () -> Int,
    val resolution: () -> String,
    val speedLimit: () -> String,
    val driver: () -> String,
    val onPreset: (Int) -> Unit
)

/**
 * The in-game side panel, with the performance card floating beside it. It is drawn from the classic
 * menu (the same items, in the same order) and every tap goes through that menu's own handler, so no
 * option can be lost or behave differently. [tick] makes it re-read the menu and the card, and take
 * focus again, every time the drawer opens.
 */
@Composable
fun InGameMenuPanel(
    menu: Menu,
    game: Game?,
    tick: Int,
    performance: PerformanceInfo?,
    onItem: (Int) -> Unit
) {
    var localTick by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    // Opening the drawer hands the controller to the panel: without this no view holds focus and
    // neither the d-pad nor the A button reach the rows.
    LaunchedEffect(tick) {
        // Wait for the drawer to finish sliding in: the panel can't take focus before it is laid out.
        delay(200)
        runCatching { first.requestFocus() }
    }

    Row(
        Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.systemBars),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Panel(menu, game, tick + localTick, first) { id ->
            onItem(id)
            localTick++
        }
        if (performance != null) {
            PerformanceCard(performance, tick + localTick) { localTick++ }
        }
    }
}

@Composable
private fun Panel(menu: Menu, game: Game?, version: Int, first: FocusRequester, onItem: (Int) -> Unit) {
    val context = LocalContext.current
    val items = remember(version) { (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible } }
    val pause = items.firstOrNull { it.itemId == R.id.menu_pause_emulation }
    val exit = items.firstOrNull { it.itemId == R.id.menu_exit }
    val special = setOf(
        R.id.menu_pause_emulation,
        R.id.menu_exit
    )
    val rest = items.filter { it.itemId !in special }

    Column(
        Modifier
            .width(400.dp)
            .fillMaxHeight()
            .background(IronLemonColors.Background.copy(alpha = 0.97f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Header(game)

        pause?.let {
            val resumes = it.title?.toString() == context.getString(R.string.emulation_unpause)
            IronLemonButton(
                text = it.title?.toString().orEmpty(),
                iconRes = if (resumes) R.drawable.ic_play else R.drawable.ic_pause,
                primary = true,
                height = 46.dp,
                modifier = Modifier.fillMaxWidth().focusRequester(first)
            ) { onItem(it.itemId) }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(IronLemonColors.Outline))

        // The other options, two to a row so more of them fit on a handheld's short screen.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rest.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEach { item -> MenuRow(item, Modifier.weight(1f)) { onItem(item.itemId) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        exit?.let {
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .lemonInteractive(
                        shape = shape,
                        cornerRadius = 16.dp,
                        glowColor = IronLemonColors.Red,
                        onClick = { onItem(it.itemId) }
                    )
                    .clip(shape)
                    .border(1.dp, IronLemonColors.Red.copy(alpha = 0.55f), shape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_exit),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(IronLemonColors.Red),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                BasicText(it.title?.toString().orEmpty(), style = IronLemonType.Button.copy(color = IronLemonColors.Red, fontSize = 15.sp))
            }
        }
    }
}

@Composable
private fun PerformanceCard(info: PerformanceInfo, version: Int, onChanged: () -> Unit) {
    val active = remember(version) { info.activePreset() }
    val resolution = remember(version) { info.resolution() }
    val speed = remember(version) { info.speedLimit() }
    val driver = remember(version) { info.driver() }
    val shape = RoundedCornerShape(24.dp)

    Column(
        Modifier
            .padding(top = 20.dp)
            .width(300.dp)
            .heightIn(max = 300.dp)
            .clip(shape)
            .background(IronLemonColors.Background.copy(alpha = 0.97f))
            .border(1.dp, IronLemonColors.Lemon.copy(alpha = 0.25f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BasicText(stringResource(R.string.lemon_perf_title).uppercase(), style = IronLemonType.Label)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            info.presetLabels.forEachIndexed { index, label ->
                val selected = index == active
                val chipShape = RoundedCornerShape(14.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .lemonInteractive(
                            shape = chipShape,
                            cornerRadius = 14.dp,
                            focusScale = 1.04f,
                            onClick = {
                                info.onPreset(index)
                                onChanged()
                            }
                        )
                        .clip(chipShape)
                        .background(if (selected) IronLemonColors.Lemon else IronLemonColors.SurfaceRaised)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        label,
                        style = IronLemonType.Button.copy(
                            fontSize = 12.sp,
                            color = if (selected) IronLemonColors.OnLemon else IronLemonColors.Text
                        ),
                        maxLines = 2
                    )
                }
            }
        }
        InfoRow(stringResource(R.string.lemon_perf_resolution), resolution)
        InfoRow(stringResource(R.string.lemon_perf_speed), speed)
        InfoRow(stringResource(R.string.lemon_perf_driver), driver)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        BasicText(label, style = IronLemonType.Body.copy(fontSize = 13.sp))
        BasicText(
            value,
            style = IronLemonType.Button.copy(fontSize = 13.sp),
            maxLines = 2,
            modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false)
        )
    }
}

@Composable
private fun Header(game: Game?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val icon = game?.let { rememberGameIcon(it) }
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .size(40.dp)
                .clip(shape)
                .background(IronLemonColors.Surface)
        ) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.matchParentSize())
            }
        }
        BasicText(
            text = game?.title.orEmpty(),
            style = IronLemonType.Heading.copy(fontSize = 17.sp),
            maxLines = 2,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MenuRow(item: MenuItem, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val icon = itemIcon(item)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .lemonInteractive(shape = shape, cornerRadius = 14.dp, focusScale = 1.03f, onClick = onClick)
            .clip(shape)
            .background(IronLemonColors.SurfaceRaised.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, colorFilter = ColorFilter.tint(IronLemonColors.Lemon), modifier = Modifier.size(18.dp))
        } else {
            Spacer(Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        BasicText(item.title?.toString().orEmpty(), style = IronLemonType.Button.copy(fontSize = 13.sp), maxLines = 2)
    }
}

/** The item's own icon (some are set while playing), else one picked for its role. */
@Composable
private fun itemIcon(item: MenuItem): ImageBitmap? {
    val context = LocalContext.current
    item.icon?.let { return runCatching { it.toBitmap().asImageBitmap() }.getOrNull() }
    val res = when (item.itemId) {
        R.id.menu_overlay_controls -> R.drawable.ic_overlay
        R.id.menu_settings, R.id.menu_settings_per_game -> R.drawable.ic_settings
        R.id.menu_controls -> R.drawable.ic_controller
        R.id.menu_lock_drawer -> R.drawable.ic_lock
        else -> return null
    }
    return remember(res) {
        androidx.core.content.ContextCompat.getDrawable(context, res)
            ?.let { runCatching { it.toBitmap().asImageBitmap() }.getOrNull() }
    }
}
