// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project / Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.ui.modern

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ironlemon.ironlemon_emulator.R
import kotlin.math.roundToInt

/**
 * A slider that works with a finger and with a gamepad: tap or drag to set it, or focus it and
 * press left/right on the D-pad.
 */
@Composable
fun IronLemonSlider(value: Int, steps: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    var current by remember(value) { mutableIntStateOf(value.coerceIn(0, steps.coerceAtLeast(1))) }
    var widthPx by remember { mutableIntStateOf(1) }
    val max = steps.coerceAtLeast(1)

    fun set(v: Int) {
        val clamped = v.coerceIn(0, max)
        if (clamped != current) {
            current = clamped
            onChange(clamped)
        }
    }

    fun fromX(x: Float, thumb: Float): Int {
        val span = (widthPx - 2 * thumb).coerceAtLeast(1f)
        return (((x - thumb) / span).coerceIn(0f, 1f) * max).roundToInt()
    }

    val thumbRadius = with(androidx.compose.ui.platform.LocalDensity.current) { 11.dp.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (it.key) {
                        Key.DirectionLeft -> { set(current - 1); true }
                        Key.DirectionRight -> { set(current + 1); true }
                        else -> false
                    }
                }
            }
            .focusable(interactionSource = source)
            .pointerInput(max) { detectTapGestures { set(fromX(it.x, thumbRadius)) } }
            .pointerInput(max) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    set(fromX(change.position.x, thumbRadius))
                }
            }
            .drawBehind {
                val trackHeight = 6.dp.toPx()
                val top = (size.height - trackHeight) / 2f
                val left = thumbRadius
                val span = (size.width - 2 * thumbRadius).coerceAtLeast(1f)
                val fraction = current.toFloat() / max
                drawRoundRect(
                    IronLemonColors.SurfaceRaised,
                    Offset(left, top),
                    Size(span, trackHeight),
                    CornerRadius(trackHeight / 2)
                )
                drawRoundRect(
                    IronLemonColors.Lemon,
                    Offset(left, top),
                    Size(span * fraction, trackHeight),
                    CornerRadius(trackHeight / 2)
                )
                val cx = left + span * fraction
                if (focused) {
                    drawCircle(IronLemonColors.Lemon.copy(alpha = 0.28f), thumbRadius * 1.9f, Offset(cx, size.height / 2))
                }
                drawCircle(
                    if (focused) IronLemonColors.Lemon else IronLemonColors.Text,
                    thumbRadius * (if (focused) 1.15f else 1f),
                    Offset(cx, size.height / 2)
                )
            }
    )
}

/** The little "more options" button at the end of a mapped button/stick row. */
@Composable
fun RowOptionsButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .lemonInteractive(shape = CircleShape, cornerRadius = 18.dp, role = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_more_vert),
            contentDescription = stringResource(R.string.lemon_more_options),
            colorFilter = ColorFilter.tint(IronLemonColors.TextMuted),
            modifier = Modifier.size(20.dp)
        )
    }
}
