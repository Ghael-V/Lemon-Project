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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    val pulse by rememberInfiniteTransition(label = "lemonPulse").animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "lemonPulseValue"
    )

    val glow = maxOf(focusGlow * 0.9f, selectedGlow * pulse * 0.75f)

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawBehind {
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
        // A gamepad's A button clicks the focused item, like Enter does.
        .onKeyEvent {
            if (it.key == Key.ButtonA && it.type == KeyEventType.KeyUp) {
                onClick()
                true
            } else {
                false
            }
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
