// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.overlay.model

/** What pressing an on-screen button does. Saved in the config as these numbers. */
object OverlayPressMode {
    const val NORMAL = 0

    /** A tap holds the button down, the next tap lets it go. */
    const val TOGGLE = 1

    /** The button is pressed repeatedly while a finger is on it. */
    const val TURBO = 2
}

data class OverlayControlData(
    val id: String,
    var enabled: Boolean,
    var landscapePosition: Pair<Double, Double>,
    var portraitPosition: Pair<Double, Double>,
    var foldablePosition: Pair<Double, Double>,
    var individualScale: Float,
    var pressMode: Int = OverlayPressMode.NORMAL
) {
    fun positionFromLayout(layout: OverlayLayout): Pair<Double, Double> =
        when (layout) {
            OverlayLayout.Landscape -> landscapePosition
            OverlayLayout.Portrait -> portraitPosition
            OverlayLayout.Foldable -> foldablePosition
        }
}
