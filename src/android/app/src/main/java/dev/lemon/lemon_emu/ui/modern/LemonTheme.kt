// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.lemon.lemon_emu.R

/** Lemon's palette: warm near-black surfaces, lemon yellow for the main action, red only for danger. */
object LemonColors {
    val Background = Color(0xFF0F0E0C)
    val Surface = Color(0xFF1F1D1A)
    val SurfaceRaised = Color(0xFF2A2723)
    val Outline = Color(0xFF3A3631)
    val Text = Color(0xFFF5F0E6)
    val TextMuted = Color(0xFFBDB5A8)
    val Lemon = Color(0xFFFFD43B)
    val OnLemon = Color(0xFF1A1814)
    val Red = Color(0xFFF0432E)
}

/** Title face (Bricolage Grotesque) and body face (DM Sans), both variable fonts bundled in res/font. */
@OptIn(ExperimentalTextApi::class)
object LemonFonts {
    private fun face(resId: Int, weight: Int) = Font(
        resId = resId,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight))
    )

    val Title: FontFamily = FontFamily(
        face(R.font.bricolage_grotesque, 600),
        face(R.font.bricolage_grotesque, 700),
        face(R.font.bricolage_grotesque, 800)
    )
    val Body: FontFamily = FontFamily(
        face(R.font.dm_sans, 400),
        face(R.font.dm_sans, 500),
        face(R.font.dm_sans, 600),
        face(R.font.dm_sans, 700)
    )
}

object LemonType {
    val Display = TextStyle(
        fontFamily = LemonFonts.Title,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        letterSpacing = (-0.3).sp,
        color = LemonColors.Text
    )
    val Heading = TextStyle(
        fontFamily = LemonFonts.Title,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 20.sp,
        color = LemonColors.Text
    )
    val Button = TextStyle(
        fontFamily = LemonFonts.Body,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        color = LemonColors.Text
    )
    val Body = TextStyle(
        fontFamily = LemonFonts.Body,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        color = LemonColors.TextMuted
    )
    val Label = TextStyle(
        fontFamily = LemonFonts.Body,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.2.sp,
        color = LemonColors.Lemon
    )
}
