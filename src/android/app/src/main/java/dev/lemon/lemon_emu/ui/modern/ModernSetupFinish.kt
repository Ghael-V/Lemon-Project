// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lemon.lemon_emu.R

/** What the final step shows about each earlier item: its name and whether it was set up. */
data class SetupSummaryItem(val title: String, val done: Boolean)

/**
 * The last step of first-run setup: a confirmation, a recap of what is ready (and what can still
 * be added later from Settings) and the button that opens the library.
 */
@Composable
fun ModernSetupFinish(
    title: String,
    description: String,
    summary: List<SetupSummaryItem>,
    actionText: String,
    onAction: () -> Unit
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (landscape) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier
                    .weight(0.9f)
                    .verticalScroll(rememberScrollState())
            ) {
                FinishHeader(title, description, centered = false)
            }
            Column(
                Modifier
                    .weight(1.1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FinishRecap(summary)
                LemonButton(
                    text = actionText,
                    primary = true,
                    height = 56.dp,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onAction
                )
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            FinishHeader(title, description, centered = true)
            Spacer(Modifier.height(28.dp))
            FinishRecap(summary)
            Spacer(Modifier.height(24.dp))
            LemonButton(
                text = actionText,
                primary = true,
                height = 56.dp,
                modifier = Modifier.fillMaxWidth(),
                onClick = onAction
            )
        }
    }
}

@Composable
private fun FinishHeader(title: String, description: String, centered: Boolean) {
    val align = if (centered) Alignment.CenterHorizontally else Alignment.Start
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start
    Column(horizontalAlignment = align) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(LemonColors.Lemon),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                colorFilter = ColorFilter.tint(LemonColors.OnLemon),
                modifier = Modifier.size(52.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        BasicText(
            text = title,
            style = LemonType.Display.copy(fontSize = 34.sp, textAlign = textAlign)
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = description,
            style = LemonType.Body.copy(fontSize = 17.sp, lineHeight = 25.sp, textAlign = textAlign)
        )
    }
}

@Composable
private fun FinishRecap(summary: List<SetupSummaryItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        summary.forEach { item ->
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(LemonColors.Text.copy(alpha = 0.05f))
                    .border(1.dp, LemonColors.Text.copy(alpha = 0.10f), shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (item.done) LemonColors.Lemon
                            else LemonColors.Text.copy(alpha = 0.12f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.done) {
                        Image(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(LemonColors.OnLemon),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                BasicText(
                    text = item.title,
                    style = LemonType.Button.copy(
                        fontSize = 16.sp,
                        color = if (item.done) LemonColors.Text else LemonColors.TextMuted
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (!item.done) {
                    BasicText(
                        text = stringResource(R.string.setup_later).uppercase(),
                        style = LemonType.Label.copy(fontSize = 10.sp, color = LemonColors.TextMuted)
                    )
                }
            }
        }
    }
}
