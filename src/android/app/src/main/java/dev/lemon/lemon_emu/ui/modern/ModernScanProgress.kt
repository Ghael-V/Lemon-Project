// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.utils.ScanProgress
import kotlinx.coroutines.delay
import java.util.Locale

/** A solid .nsz/.xcz being decompressed for the first time, as reported by the emulator core. */
private data class DecodeInfo(val name: String, val done: Long, val total: Long)

private fun readDecodeInfo(): DecodeInfo? {
    val raw = NativeLibrary.getNczDecodeInfo() ?: return null
    val parts = raw.split('\t')
    if (parts.size != 3) {
        return null
    }
    val done = parts[1].toLongOrNull() ?: return null
    val total = parts[2].toLongOrNull() ?: return null
    return if (total > 0) DecodeInfo(parts[0], done, total) else null
}

private fun gigabytes(bytes: Long) = String.format(Locale.getDefault(), "%.1f GB", bytes / 1073741824.0)

/**
 * Progress of a library scan in one bar. Every file adds a step; while a compressed game is being
 * decompressed for the first time, that file's step fills gradually and the text says what the
 * wait is, so a big library (or one huge file) no longer looks like the app did nothing.
 */
@Composable
fun ScanProgressCard(progress: ScanProgress, modifier: Modifier = Modifier) {
    var decode by remember { mutableStateOf<DecodeInfo?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            decode = readDecodeInfo()
            delay(400)
        }
    }

    val current = decode
    val decodeFraction = current?.let { it.done.toFloat() / it.total } ?: 0f
    val fraction = if (progress.total > 0) {
        ((progress.done + decodeFraction) / progress.total).coerceIn(0f, 1f)
    } else {
        null
    }

    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(24.dp)
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(LemonColors.Surface.copy(alpha = 0.97f))
            .border(1.dp, LemonColors.Lemon.copy(alpha = 0.30f), shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BasicText(
            text = if (progress.total > 0) {
                stringResource(R.string.scan_loading, progress.done.coerceAtMost(progress.total), progress.total)
            } else {
                stringResource(R.string.scan_listing)
            },
            style = LemonType.Button.copy(fontSize = 16.sp)
        )
        ScanBar(fraction)
        BasicText(
            text = if (current != null) {
                stringResource(R.string.scan_decoding, gigabytes(current.done), gigabytes(current.total))
            } else {
                progress.current
            },
            style = LemonType.Body.copy(fontSize = 13.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (current != null) {
            BasicText(
                text = current.name.removeSuffix(".nca"),
                style = LemonType.Body.copy(fontSize = 11.sp, color = LemonColors.TextMuted.copy(alpha = 0.7f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A thin bar: filled to [fraction], or a sweeping segment while the amount of work is not known yet. */
@Composable
private fun ScanBar(fraction: Float?) {
    val track = Modifier
        .fillMaxWidth()
        .height(6.dp)
        .clip(CircleShape)
        .background(LemonColors.Text.copy(alpha = 0.12f))
    if (fraction != null) {
        val animated by animateFloatAsState(fraction, tween(350), label = "scanFraction")
        BoxWithConstraints(track) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .width(maxWidth * animated)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(LemonColors.Lemon)
            )
        }
    } else {
        val sweep by rememberInfiniteTransition(label = "scanSweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
            label = "scanSweepOffset"
        )
        BoxWithConstraints(track) {
            val segment = maxWidth * 0.3f
            androidx.compose.foundation.layout.Box(
                Modifier
                    .offset(x = (maxWidth + segment) * sweep - segment)
                    .width(segment)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(LemonColors.Lemon)
            )
        }
    }
}
