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
import androidx.compose.foundation.layout.width
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

/** One row of a setup step: something the user can import or grant (keys, firmware, a folder...). */
data class SetupCardData(
    val iconRes: Int,
    val title: String,
    val subtitle: String,
    val done: Boolean,
    val required: Boolean,
    /** A plain call to action (e.g. "Get started") instead of a thing to set up. */
    val isAction: Boolean,
    val onClick: () -> Unit
)

/**
 * A step of first-run setup in the redesigned interface: where we are, what this step is for,
 * and one card per item to set up. Wide screens put the explanation on the left and the cards
 * on the right so nothing needs scrolling on a handheld.
 */
@Composable
fun ModernSetupPage(
    stepNumber: Int,
    stepCount: Int,
    iconRes: Int,
    title: String,
    description: String,
    cards: List<SetupCardData>,
    pageDone: Boolean
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
                    .weight(0.85f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start
            ) {
                StepHeader(stepNumber, stepCount, iconRes, title, description, centered = false)
            }
            Column(
                Modifier
                    .weight(1.15f)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                CardList(cards, pageDone)
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StepHeader(stepNumber, stepCount, iconRes, title, description, centered = true)
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CardList(cards, pageDone)
            }
        }
    }
}

@Composable
private fun StepHeader(
    stepNumber: Int,
    stepCount: Int,
    iconRes: Int,
    title: String,
    description: String,
    centered: Boolean
) {
    val align = if (centered) Alignment.CenterHorizontally else Alignment.Start
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start
    Column(horizontalAlignment = align) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 1..stepCount) {
                Box(
                    Modifier
                        .width(if (i == stepNumber) 34.dp else 18.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (i <= stepNumber) LemonColors.Lemon
                            else LemonColors.Text.copy(alpha = 0.16f)
                        )
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        BasicText(
            text = stringResource(R.string.setup_step_of, stepNumber, stepCount).uppercase(),
            style = LemonType.Label
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(LemonColors.Lemon.copy(alpha = 0.14f))
                .border(1.dp, LemonColors.Lemon.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(LemonColors.Lemon),
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        BasicText(
            text = title,
            style = LemonType.Display.copy(fontSize = 30.sp, textAlign = textAlign)
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = description,
            style = LemonType.Body.copy(fontSize = 16.sp, lineHeight = 23.sp, textAlign = textAlign)
        )
    }
}

@Composable
private fun CardList(cards: List<SetupCardData>, pageDone: Boolean) {
    cards.forEach { card ->
        if (card.isAction) {
            LemonButton(
                text = card.title,
                primary = true,
                height = 56.dp,
                modifier = Modifier.fillMaxWidth(),
                onClick = card.onClick
            )
        } else {
            SetupCard(card)
        }
    }
    if (pageDone && cards.any { !it.isAction }) {
        BasicText(
            text = stringResource(R.string.step_complete),
            style = LemonType.Label.copy(textAlign = TextAlign.Center),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

@Composable
private fun SetupCard(card: SetupCardData) {
    val shape = RoundedCornerShape(20.dp)
    val borderColor = if (card.done) LemonColors.Lemon.copy(alpha = 0.55f)
    else LemonColors.Text.copy(alpha = 0.12f)
    Row(
        Modifier
            .fillMaxWidth()
            .lemonInteractive(
                shape = shape,
                cornerRadius = 20.dp,
                glowColor = LemonColors.Lemon,
                focusScale = 1.02f,
                pressScale = 0.97f,
                onClick = card.onClick
            )
            .clip(shape)
            .background(
                if (card.done) LemonColors.Lemon.copy(alpha = 0.08f)
                else LemonColors.Text.copy(alpha = 0.05f)
            )
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(LemonColors.Lemon.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(card.iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(LemonColors.Lemon),
                modifier = Modifier.size(24.dp)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BasicText(text = card.title, style = LemonType.Button.copy(fontSize = 17.sp))
                if (card.required && !card.done) {
                    BasicText(
                        text = stringResource(R.string.setup_required).uppercase(),
                        style = LemonType.Label.copy(fontSize = 10.sp, color = LemonColors.Red)
                    )
                }
            }
            if (card.subtitle.isNotEmpty()) {
                BasicText(
                    text = card.subtitle,
                    style = LemonType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp)
                )
            }
        }
        if (card.done) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(LemonColors.Lemon),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(LemonColors.OnLemon),
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Image(
                painter = painterResource(R.drawable.ic_arrow_forward),
                contentDescription = null,
                colorFilter = ColorFilter.tint(LemonColors.TextMuted),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
