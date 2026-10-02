// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lemon.lemon_emu.model.Game
import kotlinx.coroutines.delay

/** What the loading screen shows, read from the existing loading views so their logic stays untouched. */
class LoadingSnapshot(
    val game: Game?,
    val message: String,
    val progress: Int,
    val max: Int,
    val indeterminate: Boolean
)

/**
 * The full-screen loading screen: the game's art blurred behind, its icon with a breathing glow,
 * the title, what is being done right now, and a progress bar.
 */
@Composable
fun ModernLoadingScreen(isActive: () -> Boolean, read: () -> LoadingSnapshot) {
    var snapshot by remember { mutableStateOf(read()) }
    LaunchedEffect(Unit) {
        while (true) {
            if (isActive()) snapshot = read()
            delay(200)
        }
    }
    val game = snapshot.game
    val icon = game?.let { rememberGameIcon(it) }
    val pulse by rememberInfiniteTransition(label = "loadingPulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "loadingPulseValue"
    )

    Box(Modifier.fillMaxSize().background(LemonColors.Background)) {
        Crossfade(targetState = icon, animationSpec = tween(500), label = "loadingBackdrop") { bitmap ->
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(80.dp).graphicsLayer { alpha = 0.75f }
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        LemonColors.Background.copy(alpha = 0.55f),
                        LemonColors.Background.copy(alpha = 0.92f)
                    )
                )
            )
        )

        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            val compact = maxHeight < 420.dp
            val iconSize = if (compact) 120.dp else 180.dp
            Column(
                Modifier.fillMaxSize().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val shape = RoundedCornerShape(if (compact) 26.dp else 34.dp)
                Box(
                    Modifier
                        .size(iconSize)
                        .drawBehind {
                            drawIntoCanvas { canvas ->
                                val paint = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    color = LemonColors.Lemon.copy(alpha = 0.18f * pulse).toArgb()
                                    setShadowLayer(34.dp.toPx(), 0f, 0f, LemonColors.Lemon.copy(alpha = 0.85f * pulse).toArgb())
                                }
                                val r = 34.dp.toPx()
                                canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
                            }
                        }
                        .clip(shape)
                        .background(LemonColors.Surface)
                ) {
                    if (icon != null) {
                        Image(bitmap = icon, contentDescription = null, modifier = Modifier.matchParentSize())
                    }
                }
                Spacer(Modifier.height(if (compact) 14.dp else 24.dp))
                BasicText(
                    text = game?.title.orEmpty(),
                    style = LemonType.Display.copy(fontSize = if (compact) 24.sp else 32.sp, textAlign = TextAlign.Center),
                    maxLines = 2
                )
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = snapshot.message,
                    style = LemonType.Body.copy(fontSize = 16.sp, textAlign = TextAlign.Center),
                    maxLines = 1
                )
                Spacer(Modifier.height(if (compact) 14.dp else 22.dp))
                LoadingBar(snapshot, Modifier.width(360.dp))
            }
        }
    }
}

@Composable
private fun LoadingBar(snapshot: LoadingSnapshot, modifier: Modifier) {
    val determinate = !snapshot.indeterminate && snapshot.max > 0
    val target = if (determinate) (snapshot.progress.toFloat() / snapshot.max).coerceIn(0f, 1f) else 0f
    val fraction by animateFloatAsState(target, tween(250), label = "loadingFraction")
    val sweep by rememberInfiniteTransition(label = "loadingSweep").animateFloat(
        initialValue = -0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "loadingSweepValue"
    )
    val shape = RoundedCornerShape(50)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(8.dp).clip(shape).background(LemonColors.Text.copy(alpha = 0.14f))
        ) {
            if (determinate) {
                Box(Modifier.fillMaxSize(fraction).clip(shape).background(LemonColors.Lemon))
            } else {
                Box(
                    Modifier
                        .offset(x = maxWidth * sweep)
                        .width(maxWidth * 0.4f)
                        .fillMaxSize()
                        .clip(shape)
                        .background(LemonColors.Lemon)
                )
            }
        }
        if (determinate) {
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "${snapshot.progress} / ${snapshot.max}",
                style = LemonType.Body.copy(fontSize = 13.sp)
            )
        }
    }
}
