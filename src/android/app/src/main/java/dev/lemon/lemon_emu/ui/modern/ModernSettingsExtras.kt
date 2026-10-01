// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

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
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.features.settings.model.view.FxButtonSetting
import dev.lemon.lemon_emu.features.settings.model.view.FxPresetSetting
import dev.lemon.lemon_emu.features.settings.model.view.FxShaderCardSetting
import dev.lemon.lemon_emu.features.settings.model.view.FxToolbarSetting
import dev.lemon.lemon_emu.features.settings.model.FxUniformSliderSetting
import dev.lemon.lemon_emu.utils.NativePostProcessing
import kotlin.math.roundToInt

/**
 * A slider that works with a finger and with a gamepad: tap or drag to set it, or focus it and
 * press left/right on the D-pad.
 */
@Composable
fun LemonSlider(value: Int, steps: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
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
                    LemonColors.SurfaceRaised,
                    Offset(left, top),
                    Size(span, trackHeight),
                    CornerRadius(trackHeight / 2)
                )
                drawRoundRect(
                    LemonColors.Lemon,
                    Offset(left, top),
                    Size(span * fraction, trackHeight),
                    CornerRadius(trackHeight / 2)
                )
                val cx = left + span * fraction
                if (focused) {
                    drawCircle(LemonColors.Lemon.copy(alpha = 0.28f), thumbRadius * 1.9f, Offset(cx, size.height / 2))
                }
                drawCircle(
                    if (focused) LemonColors.Lemon else LemonColors.Text,
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
            colorFilter = ColorFilter.tint(LemonColors.TextMuted),
            modifier = Modifier.size(20.dp)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Post-processing effects
// ---------------------------------------------------------------------------------------------

@Composable
private fun FxPill(text: String, iconRes: Int?, enabled: Boolean, danger: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(42.dp)
            .lemonInteractive(
                shape = shape,
                cornerRadius = 21.dp,
                glowColor = if (danger) LemonColors.Red else LemonColors.Lemon,
                focusScale = 1.04f,
                onClick = { if (enabled) onClick() }
            )
            .clip(shape)
            .background(if (danger) LemonColors.Red.copy(alpha = 0.18f) else LemonColors.SurfaceRaised.copy(alpha = 0.8f))
            .border(1.dp, (if (danger) LemonColors.Red else LemonColors.Text).copy(alpha = 0.18f), shape)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(if (danger) LemonColors.Red else LemonColors.Lemon),
                modifier = Modifier.size(18.dp)
            )
        }
        BasicText(
            text,
            style = LemonType.Button.copy(
                fontSize = 14.sp,
                color = if (danger) LemonColors.Red else LemonColors.Text
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Add effect / presets / save preset / remove all. */
@Composable
fun FxToolbarRow(item: FxToolbarSetting, onCreatePreset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FxPill(
                stringResource(item.addLabelId),
                if (item.listOpen) R.drawable.ic_clear else R.drawable.ic_add,
                true, false, Modifier.weight(1f), item.onAdd
            )
            FxPill(item.presetLabel, null, true, false, Modifier.weight(1f), item.onPresets)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FxPill(
                stringResource(R.string.post_processing_preset_new),
                R.drawable.ic_add, item.hasEffects, false,
                Modifier.weight(1f).graphicsLayer { alpha = if (item.hasEffects) 1f else 0.45f }, onCreatePreset
            )
            FxPill(
                stringResource(R.string.post_processing_remove_all),
                R.drawable.ic_delete, item.hasEffects, true,
                Modifier.weight(1f).graphicsLayer { alpha = if (item.hasEffects) 1f else 0.45f }, item.onRemoveAll
            )
        }
    }
}

@Composable
fun FxPresetRow(item: FxPresetSetting) {
    Row(
        Modifier
            .fillMaxWidth()
            .lemonInteractive(
                shape = RoundedCornerShape(0.dp), cornerRadius = 0.dp, focusScale = 1.0f, pressScale = 0.985f,
                role = null, onClick = item.onApply
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(item.title, style = LemonType.Button.copy(fontSize = 15.sp))
            if (item.description.isNotEmpty()) {
                BasicText(item.description, style = LemonType.Body.copy(fontSize = 13.sp), maxLines = 2)
            }
        }
        if (item.deletable) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .lemonInteractive(shape = CircleShape, cornerRadius = 18.dp, glowColor = LemonColors.Red, role = null, onClick = item.onDelete),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.post_processing_preset_delete),
                    colorFilter = ColorFilter.tint(LemonColors.Red),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun FxButtonRow(item: FxButtonSetting) {
    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
        FxPill(item.title, null, true, false, Modifier.fillMaxWidth(), item.onClick)
    }
}

/** One effect: its name, and (when opened) a slider for each of its settings. */
@Composable
fun FxShaderCardRow(item: FxShaderCardSetting) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .lemonInteractive(
                    shape = RoundedCornerShape(0.dp), cornerRadius = 0.dp, focusScale = 1.0f, pressScale = 0.985f,
                    role = null, onClick = item.onToggle
                )
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(item.title, style = LemonType.Button.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.description.isNotEmpty()) {
                    BasicText(item.description, style = LemonType.Body.copy(fontSize = 13.sp), maxLines = 2)
                }
            }
            Image(
                painter = painterResource(R.drawable.ic_dropdown_arrow),
                contentDescription = null,
                colorFilter = ColorFilter.tint(LemonColors.Lemon),
                modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = if (item.expanded) 180f else 0f }
            )
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .lemonInteractive(shape = CircleShape, cornerRadius = 18.dp, glowColor = LemonColors.Red, role = null, onClick = item.onRemove),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.post_processing_remove),
                    colorFilter = ColorFilter.tint(LemonColors.Red),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (item.expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (uniform in item.uniforms) {
                    if (uniform.uiType == NativePostProcessing.UI_HIDDEN) continue
                    for (component in 0 until uniform.components) {
                        FxUniformRow(item.index, uniform, component)
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterStart) {
                    FxPill(stringResource(R.string.post_processing_preset_reset), null, true, false, Modifier, item.onReset)
                }
            }
        }
    }
}

@Composable
private fun FxUniformRow(index: Int, uniform: NativePostProcessing.Uniform, component: Int) {
    val setting = remember(index, uniform, component) { FxUniformSliderSetting(index, uniform, component) }
    val steps = uniform.steps
    var shown by remember(setting) { mutableIntStateOf(setting.getInt(false).coerceIn(0, steps)) }
    val title = if (uniform.components > 1) uniform.label + " [" + component + "]" else uniform.label
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(title, style = LemonType.Body.copy(fontSize = 13.sp, color = LemonColors.Text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(uniform.describe(shown), style = LemonType.Button.copy(fontSize = 13.sp, color = LemonColors.Lemon), maxLines = 1)
        }
        LemonSlider(
            value = shown,
            steps = steps,
            onChange = {
                shown = it
                setting.setInt(it)
            }
        )
    }
}
