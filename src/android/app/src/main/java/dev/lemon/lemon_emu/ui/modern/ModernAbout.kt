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
import androidx.compose.foundation.layout.widthIn
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
import dev.lemon.lemon_emu.R

class AboutActions(
    val onBack: () -> Unit,
    val onCopyVersion: () -> Unit,
    val onLicenses: () -> Unit,
    val onLink: (Int) -> Unit
)

/** The About screen: what Lemon is, its build, where to find it, how to support it, and the credits. */
@Composable
fun ModernAbout(versionText: String, actions: AboutActions) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(
        Modifier
            .fillMaxSize()
            .background(LemonColors.Background)
            .background(
                Brush.radialGradient(
                    listOf(LemonColors.Lemon.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(1000f, 0f),
                    radius = 900f
                )
            )
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                LemonIconButton(R.drawable.ic_back, stringResource(R.string.back), onClick = actions.onBack)
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(if (landscape) 96.dp else 132.dp)
                )
                BasicText(
                    stringResource(R.string.app_name),
                    style = LemonType.Display.copy(fontSize = 34.sp, textAlign = TextAlign.Center)
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    stringResource(R.string.about_app_description),
                    style = LemonType.Body.copy(fontSize = 15.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.widthIn(max = 560.dp)
                )
                Spacer(Modifier.height(14.dp))

                // The build, tap to copy it for a bug report.
                val versionShape = RoundedCornerShape(50)
                Row(
                    Modifier
                        .lemonInteractive(shape = versionShape, cornerRadius = 24.dp, onClick = actions.onCopyVersion)
                        .clip(versionShape)
                        .background(LemonColors.SurfaceRaised.copy(alpha = 0.8f))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BasicText(stringResource(R.string.build).uppercase(), style = LemonType.Label)
                    BasicText(versionText, style = LemonType.Button.copy(fontSize = 14.sp))
                }

                Spacer(Modifier.height(20.dp))
                val links = @Composable {
                    LinkButton(R.drawable.ic_github, stringResource(R.string.github_link_button)) { actions.onLink(R.string.github_link) }
                    LinkButton(R.drawable.ic_discord, stringResource(R.string.discord_link_button)) { actions.onLink(R.string.discord_link) }
                    LinkButton(R.drawable.ic_coffee, stringResource(R.string.kofi_link_button)) { actions.onLink(R.string.kofi_link) }
                    LinkButton(R.drawable.ic_coffee, stringResource(R.string.buymeacoffee_link_button)) { actions.onLink(R.string.buymeacoffee_link) }
                }
                if (landscape) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { links() }
                } else {
                    Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { links() }
                }

                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier.widthIn(max = 700.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InfoCard(
                        title = stringResource(R.string.licenses),
                        body = stringResource(R.string.licenses_description),
                        onClick = actions.onLicenses
                    )
                    InfoCard(
                        title = stringResource(R.string.lemon_about_credits_title),
                        body = stringResource(R.string.lemon_about_credits_body) + "\n\n" + stringResource(R.string.logo_credit),
                        onClick = { actions.onLink(R.string.contributors_link) }
                    )
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun LinkButton(iconRes: Int, label: String, onClick: () -> Unit) {
    LemonButton(text = label, iconRes = iconRes, height = 48.dp, modifier = Modifier.widthIn(min = 150.dp), onClick = onClick)
}

@Composable
private fun InfoCard(title: String, body: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .lemonInteractive(shape = shape, cornerRadius = 22.dp, focusScale = 1.02f, pressScale = 0.98f, onClick = onClick)
            .clip(shape)
            .background(LemonColors.Surface)
            .border(1.dp, LemonColors.Outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BasicText(title, style = LemonType.Button.copy(fontSize = 17.sp))
        BasicText(body, style = LemonType.Body.copy(fontSize = 14.sp))
    }
}
