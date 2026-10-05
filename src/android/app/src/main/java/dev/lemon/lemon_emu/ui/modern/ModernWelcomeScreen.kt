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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lemon.lemon_emu.BuildConfig
import dev.lemon.lemon_emu.R

/** The first screen of first-run setup: what Lemon is, then "Get started" into the steps. */
@Composable
fun ModernWelcomeScreen(onStart: () -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(
        Modifier
            .fillMaxSize()
            .background(LemonColors.Background)
            .background(
                Brush.radialGradient(
                    listOf(LemonColors.Lemon.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(1100f, 0f),
                    radius = 900f
                )
            )
            .background(
                Brush.radialGradient(
                    listOf(LemonColors.Red.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(0f, 1300f),
                    radius = 800f
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(if (landscape) 96.dp else 128.dp)
            )
            BasicText(
                text = stringResource(R.string.lemon_welcome_title),
                style = LemonType.Display.copy(fontSize = if (landscape) 38.sp else 34.sp, textAlign = TextAlign.Center)
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = stringResource(R.string.lemon_welcome_subtitle),
                style = LemonType.Body.copy(fontSize = 18.sp, color = LemonColors.Text.copy(alpha = 0.85f), textAlign = TextAlign.Center)
            )
            Spacer(Modifier.height(24.dp))

            val features = listOf(
                // Lite doesn't carry the Lemon-Ade driver; it is about its lighter settings instead.
                R.drawable.ic_graphics to
                    if (BuildConfig.LITE) R.string.lemon_welcome_feature_lite else R.string.lemon_welcome_feature_driver,
                R.drawable.ic_install to R.string.lemon_welcome_feature_migrate,
                R.drawable.ic_save to R.string.lemon_welcome_feature_savestate
            )
            if (landscape) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    features.forEach { (icon, text) -> FeatureCard(icon, stringResource(text), Modifier.weight(1f)) }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    features.forEach { (icon, text) -> FeatureCard(icon, stringResource(text), Modifier.fillMaxWidth()) }
                }
            }

            Spacer(Modifier.height(28.dp))
            LemonButton(
                text = stringResource(R.string.lemon_welcome_start),
                primary = true,
                height = 56.dp,
                modifier = Modifier.width(360.dp),
                onClick = onStart
            )
        }
    }
}

@Composable
private fun FeatureCard(iconRes: Int, text: String, modifier: Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .clip(shape)
            .background(LemonColors.Text.copy(alpha = 0.05f))
            .border(1.dp, LemonColors.Lemon.copy(alpha = 0.18f), shape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(LemonColors.Lemon),
            modifier = Modifier.size(28.dp)
        )
        BasicText(text = text, style = LemonType.Button.copy(fontSize = 17.sp))
    }
}
