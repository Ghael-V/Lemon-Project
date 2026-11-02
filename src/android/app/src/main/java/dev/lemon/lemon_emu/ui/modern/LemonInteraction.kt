// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lemon's interaction language, shared by every button and card:
 * - pressed: squishes in, and springs back with a little overshoot on release
 * - focused (controller / keyboard): grows slightly and gets a soft glow
 * - selected: a lemon outline with a slowly breathing glow
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.lemonInteractive(
    shape: Shape,
    cornerRadius: Dp,
    selected: Boolean = false,
    glowColor: Color = LemonColors.Lemon,
    focusScale: Float = 1.05f,
    pressScale: Float = 0.94f,
    role: Role? = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    // The gamepad A button's press in progress (see onKeyEvent below).
    val aButton = remember { AButtonPress() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()

    // A spring that under-damps on the way back: the "squish".
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> pressScale
            focused -> focusScale
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "lemonScale"
    )
    val focusGlow by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(180),
        label = "lemonFocusGlow"
    )
    val selectedGlow by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(260),
        label = "lemonSelectedGlow"
    )
    // Only a selected item pulses, and the glow is worked out while drawing. An endless pulse on
    // every item, read here, recomposed the whole in-game menu on every display frame while a game
    // ran, even with its drawer closed: most of a CPU core.
    val pulse = if (selected) {
        rememberInfiniteTransition(label = "lemonPulse").animateFloat(
            initialValue = 0.65f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1400, easing = LinearEasing),
                RepeatMode.Reverse
            ),
            label = "lemonPulseValue"
        )
    } else {
        null
    }

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawBehind {
            val glow = maxOf(focusGlow * 0.9f, selectedGlow * (pulse?.value ?: 1f) * 0.75f)
            if (glow > 0.01f) {
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = glowColor.copy(alpha = 0.2f * glow).toArgb()
                        setShadowLayer(
                            22.dp.toPx(),
                            0f,
                            0f,
                            glowColor.copy(alpha = glow).toArgb()
                        )
                    }
                    val radius = cornerRadius.toPx()
                    canvas.nativeCanvas.drawRoundRect(
                        0f, 0f, size.width, size.height, radius, radius, paint
                    )
                }
            }
        }
        .then(
            if (selected) {
                Modifier.border(3.dp, glowColor.copy(alpha = selectedGlow), shape)
            } else {
                Modifier
            }
        )
        // A gamepad's A button clicks the focused item, like Enter does, and long-clicks it when
        // held. All of its events are taken here: Android turns an A press nobody handles into a
        // D-pad center press, which the clickable below clicks on as well, so one press of A
        // clicked twice (a game was launched twice). A held A repeats its key down, and those
        // repeats are not new presses: once a long click opened a menu, they pressed its first
        // item, and letting go of A clicked it.
        // Focus moving away while A is held (a long click opening a menu) sends A's release to
        // the new item, so this one would stay pressed and swallow the next A press: end the press
        // here instead.
        .onFocusChanged { state ->
            if (!state.isFocused) {
                aButton.longPress?.cancel()
                aButton.longPress = null
                aButton.press?.let { interactionSource.tryEmit(PressInteraction.Cancel(it)) }
                aButton.press = null
            }
        }
        .onKeyEvent {
            if (it.key != Key.ButtonA) {
                return@onKeyEvent false
            }
            val repeat = it.nativeKeyEvent.repeatCount > 0
            when (it.type) {
                KeyEventType.KeyDown -> if (aButton.press == null && !repeat) {
                    val press = PressInteraction.Press(Offset.Zero)
                    aButton.press = press
                    aButton.longClicked = false
                    interactionSource.tryEmit(press)
                    if (onLongClick != null) {
                        aButton.longPress = scope.launch {
                            delay(longPressTimeout)
                            aButton.longClicked = true
                            onLongClick()
                        }
                    }
                }
                KeyEventType.KeyUp -> aButton.press?.let { press ->
                    aButton.press = null
                    aButton.longPress?.cancel()
                    aButton.longPress = null
                    interactionSource.tryEmit(PressInteraction.Release(press))
                    if (!aButton.longClicked) {
                        onClick()
                    }
                }
            }
            true
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick
        )
        // A plain clickable can't take focus while the window is in touch mode (say, right after
        // the in-game menu was swiped open), leaving a controller with nothing to move around.
        .focusable(interactionSource = interactionSource)
}

private class AButtonPress {
    var press: PressInteraction.Press? = null
    var longPress: Job? = null
    var longClicked = false
}
