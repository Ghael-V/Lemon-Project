// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.utils

import dev.lemon.lemon_emu.features.settings.utils.SettingsFile
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.overlay.model.OverlayControlData

/**
 * Lets the layout of the on-screen controls (positions, sizes, which controls are shown, and the
 * presets) be kept for one game only. While a game runs, edits go to whichever layout the touch
 * controls are using; when an edit is finished the user chooses where it is kept.
 */
object PerGameOverlay {
    /** The layout the touch controls use right now (the game's own if it has one). */
    fun snapshot(): Array<OverlayControlData> = NativeConfig.getOverlayControlData()

    fun changed(before: Array<OverlayControlData>): Boolean =
        !before.contentEquals(NativeConfig.getOverlayControlData())

    /** Puts the layout back as it was before the edit. */
    fun discard(before: Array<OverlayControlData>, wasCustom: Boolean) {
        NativeConfig.setOverlayControlData(before)
        if (!wasCustom) {
            NativeConfig.saveGlobalConfig()
        }
    }

    /**
     * Keeps [edited] for [game] alone. If the edit was made on the global layout, the global layout
     * goes back to [before].
     */
    fun saveForThisGame(
        game: Game,
        before: Array<OverlayControlData>,
        wasCustom: Boolean,
        edited: Array<OverlayControlData>
    ) {
        if (!wasCustom) {
            NativeConfig.setOverlayControlDataFor(before, true)
            NativeConfig.saveGlobalConfig()
        }
        SettingsFile.loadCustomConfig(game)
        NativeConfig.setOverlayControlDataFor(edited, false)
        NativeConfig.setCustomOverlayActive(true)
        NativeConfig.savePerGameConfig()
        NativeConfig.unloadPerGameConfig()
    }

    /**
     * Keeps [edited] for every game. If [game] had a layout of its own it is dropped, so it follows
     * the global one like the rest.
     */
    fun saveForAllGames(game: Game, edited: Array<OverlayControlData>, wasCustom: Boolean) {
        if (wasCustom) {
            NativeConfig.setOverlayControlDataFor(edited, true)
            NativeConfig.setCustomOverlayActive(false)
            NativeConfig.saveGlobalConfig()
            SettingsFile.loadCustomConfig(game)
            NativeConfig.setCustomOverlayActive(false)
            NativeConfig.savePerGameConfig()
            NativeConfig.unloadPerGameConfig()
        } else {
            NativeConfig.saveGlobalConfig()
        }
    }
}
