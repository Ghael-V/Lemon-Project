// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lemon.lemon_emu.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pull down to refresh, for both library layouts: the portrait grid scrolls vertically, so the pull
 * is what is left over when it is already at the top (nested scroll); the landscape carousel does not
 * scroll vertically at all, so there a plain vertical drag is the pull. While pulling, a small
 * pill says to keep going and, past the threshold, to let go.
 */
@Composable
fun PullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val maxPull = threshold * 1.6f
    var pull by remember { mutableFloatStateOf(0f) }
    val isRefreshing by rememberUpdatedState(refreshing)
    val refresh by rememberUpdatedState(onRefresh)

    fun release() {
        if (pull >= threshold && !isRefreshing) {
            refresh()
        }
        pull = 0f
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Pushing back up first gives back what was pulled, before the list scrolls.
                if (pull > 0f && available.y < 0f) {
                    val given = max(available.y, -pull)
                    pull += given
                    return Offset(0f, given)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // Only a finger drag counts: a fling that reaches the top must not start a pull.
                if (source == NestedScrollSource.UserInput && available.y > 0f && !isRefreshing) {
                    pull = (pull + available.y * 0.5f).coerceAtMost(maxPull)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                release()
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier
            .nestedScroll(connection)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { release() },
                    onDragCancel = { pull = 0f }
                ) { _, dy ->
                    if (!isRefreshing && (dy > 0f || pull > 0f)) {
                        pull = (pull + dy * 0.5f).coerceIn(0f, maxPull)
                    }
                }
            }
    ) {
        content()

        val shown by animateFloatAsState(pull, label = "pull")
        if (shown > 1f) {
            val progress = (shown / threshold).coerceIn(0f, 1f)
            BasicText(
                text = stringResource(
                    if (pull >= threshold) R.string.pull_release else R.string.pull_to_refresh
                ),
                style = LemonType.Button.copy(fontSize = 13.sp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .offset { IntOffset(0, (shown * 0.6f).roundToInt()) }
                    .alpha(progress)
                    .clip(CircleShape)
                    .background(LemonColors.Surface.copy(alpha = 0.97f))
                    .border(1.dp, LemonColors.Lemon.copy(alpha = 0.45f), CircleShape)
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            )
        }
    }
}
