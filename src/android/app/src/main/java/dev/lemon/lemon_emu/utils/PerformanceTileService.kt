// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.utils

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dev.lemon.lemon_emu.LemonApplication
import dev.lemon.lemon_emu.R

/**
 * Quick Settings tile that cycles the global performance preset (Battery/Balanced/Quality)
 * without opening the app. Applies to the global config, so it takes effect on the next game
 * launch - it doesn't hot-reload settings into an emulation session that's already running.
 */
class PerformanceTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val presets = PerformancePresets.Preset.entries
        val prefs = PreferenceManager.getDefaultSharedPreferences(LemonApplication.appContext)
        val nextIndex = (currentIndex(prefs) + 1) % presets.size
        val preset = presets[nextIndex]

        PerformancePresets.apply(preset)
        NativeConfig.saveGlobalConfig()
        prefs.edit { putInt(KEY_LAST_PRESET_INDEX, nextIndex) }

        refreshTile()
    }

    private fun currentIndex(prefs: android.content.SharedPreferences): Int =
        prefs.getInt(KEY_LAST_PRESET_INDEX, DEFAULT_PRESET_INDEX)

    private fun refreshTile() {
        val presets = PerformancePresets.Preset.entries
        val prefs = PreferenceManager.getDefaultSharedPreferences(LemonApplication.appContext)
        val preset = presets[currentIndex(prefs)]

        qsTile?.apply {
            label = getString(R.string.performance_preset)
            state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(preset.titleRes)
            }
            contentDescription = getString(preset.titleRes)
            updateTile()
        }
    }

    companion object {
        private const val KEY_LAST_PRESET_INDEX = "qs_tile_performance_preset_index"
        private const val DEFAULT_PRESET_INDEX = 1 // Balanced
    }
}
