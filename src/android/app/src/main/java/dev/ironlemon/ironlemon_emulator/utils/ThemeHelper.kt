// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.settings.model.BooleanSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.IntSetting
import dev.ironlemon.ironlemon_emulator.ui.main.ThemeProvider

object ThemeHelper {
    const val SYSTEM_BAR_ALPHA = 0.9f

    /**
     * Iron Lemon has one theme, always dark, with the colors of its Compose screens: every
     * activity uses it, so no classic screen is left in yuzu's colors.
     */
    fun setTheme(activity: AppCompatActivity) {
        setThemeMode(activity)
        activity.setTheme(R.style.Theme_IronLemon)
    }

    @ColorInt
    fun getColorWithOpacity(@ColorInt color: Int, alphaFactor: Float): Int {
        return Color.argb(
            (alphaFactor * Color.alpha(color)).roundToInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    fun setCorrectTheme(activity: AppCompatActivity) {
        val currentTheme = (activity as ThemeProvider).themeId
        setTheme(activity)
        if (currentTheme != (activity as ThemeProvider).themeId) {
            activity.recreate()
        }
    }

    fun setThemeMode(activity: AppCompatActivity) {
        activity.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        setDarkModeSystemBars(
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        )
    }

    private fun isNightMode(activity: AppCompatActivity): Boolean {
        return when (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }

    private fun setLightModeSystemBars(windowController: WindowInsetsControllerCompat) {
        windowController.isAppearanceLightStatusBars = true
        windowController.isAppearanceLightNavigationBars = true
    }

    private fun setDarkModeSystemBars(windowController: WindowInsetsControllerCompat) {
        windowController.isAppearanceLightStatusBars = false
        windowController.isAppearanceLightNavigationBars = false
    }
}

enum class Theme(val int: Int) {
    Default(0),
    MaterialYou(1);

    companion object {
        fun from(int: Int): Theme = entries.firstOrNull { it.int == int } ?: Default
    }
}
