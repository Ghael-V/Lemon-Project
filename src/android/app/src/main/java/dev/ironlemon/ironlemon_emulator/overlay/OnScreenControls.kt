// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.overlay

import android.view.InputDevice
import dev.ironlemon.ironlemon_emulator.features.settings.model.IntSetting
import dev.ironlemon.ironlemon_emulator.utils.InputHandler

/**
 * When the on-screen controller shows: Automatic (only without a physical controller, so devices
 * with built-in controls such as the AYN Odin never show it), Always or Never.
 */
object OnScreenControls {
    const val AUTOMATIC = 0
    const val ALWAYS = 1
    const val NEVER = 2

    var mode: Int
        get() = IntSetting.INPUT_OVERLAY_MODE.getInt()
        set(value) = IntSetting.INPUT_OVERLAY_MODE.setInt(value)

    /** A physical controller is connected or built in (not virtual, not a touch device). */
    fun physicalControllerPresent(): Boolean =
        InputDevice.getDeviceIds().any {
            InputHandler.isPhysicalGameController(InputDevice.getDevice(it))
        }

    fun shouldShow(): Boolean = when (mode) {
        ALWAYS -> true
        NEVER -> false
        else -> !physicalControllerPresent()
    }
}
