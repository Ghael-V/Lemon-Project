// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.utils.GameIconUtils

/** The game's icon, loaded off the main thread and cached by the shared icon loader. */
@Composable
fun rememberGameIcon(game: Game): ImageBitmap? {
    val lifecycleOwner = LocalLifecycleOwner.current
    val icon by produceState<ImageBitmap?>(initialValue = null, game.path, game.version) {
        value = runCatching {
            GameIconUtils.getGameIcon(lifecycleOwner, game).asImageBitmap()
        }.getOrNull()
    }
    return icon
}

@Composable
fun LemonButton(
    text: String,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    primary: Boolean = false,
    height: Dp = 52.dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(height / 2)
    val contentColor = if (primary) LemonColors.OnLemon else LemonColors.Text
    Row(
        modifier = modifier
            .height(height)
            .lemonInteractive(
                shape = shape,
                cornerRadius = height / 2,
                glowColor = if (primary) LemonColors.Lemon else LemonColors.Text,
                onClick = onClick
            )
            .clip(shape)
            .background(if (primary) LemonColors.Lemon else LemonColors.SurfaceRaised.copy(alpha = 0.7f))
            .then(
                if (primary) Modifier else Modifier.border(1.dp, LemonColors.Text.copy(alpha = 0.16f), shape)
            )
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(contentColor),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        BasicText(text = text, style = LemonType.Button.copy(color = contentColor))
    }
}

@Composable
fun LemonIconButton(
    iconRes: Int,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .lemonInteractive(
                shape = CircleShape,
                cornerRadius = size / 2,
                glowColor = LemonColors.Text,
                onClick = onClick
            )
            .clip(CircleShape)
            .background(LemonColors.SurfaceRaised.copy(alpha = 0.7f))
            .border(1.dp, LemonColors.Text.copy(alpha = 0.16f), CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(LemonColors.Text),
            modifier = Modifier.size(size / 2)
        )
    }
}

/** A square game tile: the game's icon, its title along the bottom, and Lemon's interaction glow. */
@Composable
fun GameTile(
    game: Game,
    size: Dp,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    val icon = rememberGameIcon(game)
    Box(
        modifier = modifier
            .size(size)
            .lemonInteractive(
                shape = shape,
                cornerRadius = 22.dp,
                selected = selected,
                role = null,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .clip(shape)
            .background(LemonColors.Surface)
            .semantics { contentDescription = game.title }
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/** Big glassy shortcut tile used in the row at the bottom of the library. */
@Composable
fun ShortcutTile(
    iconRes: Int,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .lemonInteractive(
                shape = shape,
                cornerRadius = 20.dp,
                glowColor = LemonColors.Text,
                onClick = onClick
            )
            .clip(shape)
            .background(LemonColors.SurfaceRaised.copy(alpha = 0.55f))
            .border(1.dp, LemonColors.Text.copy(alpha = 0.16f), shape)
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(LemonColors.Text),
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = label,
            style = LemonType.Button.copy(fontSize = 14.sp),
            maxLines = 1
        )
    }
}
