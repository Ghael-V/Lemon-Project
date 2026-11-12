// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project / Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.ui.modern

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
import dev.ironlemon.ironlemon_emulator.R

class AboutActions(
    val onBack: () -> Unit,
    val onCopyVersion: () -> Unit,
    val onLicenses: () -> Unit,
    val onCheckUpdates: () -> Unit,
    val onLink: (Int) -> Unit
)

/** The About screen: what Lemon is, its build, where to find it, how to support it, and the credits. */
@Composable
fun ModernAbout(versionText: String, actions: AboutActions) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(
        Modifier
            .fillMaxSize()
            .background(IronLemonColors.Background)
            .background(
                Brush.radialGradient(
                    listOf(IronLemonColors.Lemon.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(1000f, 0f),
                    radius = 900f
                )
            )
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                IronLemonIconButton(R.drawable.ic_back, stringResource(R.string.back), onClick = actions.onBack)
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_yuzu),
                    contentDescription = null,
                    modifier = Modifier.size(if (landscape) 96.dp else 132.dp)
                )
                BasicText(
                    stringResource(R.string.app_name),
                    style = IronLemonType.Display.copy(fontSize = 34.sp, textAlign = TextAlign.Center)
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    stringResource(R.string.about_app_description),
                    style = IronLemonType.Body.copy(fontSize = 15.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.widthIn(max = 560.dp)
                )
                Spacer(Modifier.height(14.dp))

                // The build, tap to copy it for a bug report.
                val versionShape = RoundedCornerShape(50)
                Row(
                    Modifier
                        .lemonInteractive(shape = versionShape, cornerRadius = 24.dp, onClick = actions.onCopyVersion)
                        .clip(versionShape)
                        .background(IronLemonColors.SurfaceRaised.copy(alpha = 0.8f))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BasicText(stringResource(R.string.build).uppercase(), style = IronLemonType.Label)
                    BasicText(versionText, style = IronLemonType.Button.copy(fontSize = 14.sp))
                }

                Spacer(Modifier.height(20.dp))
                // Every button is (icon, label, what it does). On a wide screen they sit three to a row.
                val links = listOf(
                    Triple(R.drawable.ic_refresh, stringResource(R.string.check_updates_now), actions.onCheckUpdates),
                    Triple(R.drawable.ic_website, stringResource(R.string.website_link_button)) { actions.onLink(R.string.website_link) },
                    Triple(R.drawable.ic_code, stringResource(R.string.source_link_button)) { actions.onLink(R.string.source_link) },
                    Triple(R.drawable.ic_github, stringResource(R.string.github_link_button)) { actions.onLink(R.string.github_link) },
                    Triple(R.drawable.ic_info_outline, stringResource(R.string.guide_link_button)) { actions.onLink(R.string.guide_link) },
                    Triple(R.drawable.ic_info_outline, stringResource(R.string.faq_link_button)) { actions.onLink(R.string.faq_link) },
                    Triple(R.drawable.ic_discord, stringResource(R.string.discord_link_button)) { actions.onLink(R.string.discord_link) },
                    Triple(R.drawable.ic_coffee, stringResource(R.string.kofi_link_button)) { actions.onLink(R.string.kofi_link) },
                    Triple(R.drawable.ic_coffee, stringResource(R.string.buymeacoffee_link_button)) { actions.onLink(R.string.buymeacoffee_link) }
                )
                if (landscape) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        links.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { (icon, label, onClick) -> LinkButton(icon, label, onClick) }
                            }
                        }
                    }
                } else {
                    Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        links.forEach { (icon, label, onClick) -> LinkButton(icon, label, onClick) }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier.widthIn(max = 700.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InfoCard(
                        title = stringResource(R.string.team_lemon),
                        body = stringResource(R.string.team_lemon_members) + "\n\n" + stringResource(R.string.patrons_thanks),
                        onClick = { actions.onLink(R.string.team_link) }
                    )
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
    IronLemonButton(text = label, iconRes = iconRes, height = 48.dp, modifier = Modifier.widthIn(min = 150.dp), onClick = onClick)
}

@Composable
private fun InfoCard(title: String, body: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .lemonInteractive(shape = shape, cornerRadius = 22.dp, focusScale = 1.02f, pressScale = 0.98f, onClick = onClick)
            .clip(shape)
            .background(IronLemonColors.Surface)
            .border(1.dp, IronLemonColors.Outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BasicText(title, style = IronLemonType.Button.copy(fontSize = 17.sp))
        BasicText(body, style = IronLemonType.Body.copy(fontSize = 14.sp))
    }
}
