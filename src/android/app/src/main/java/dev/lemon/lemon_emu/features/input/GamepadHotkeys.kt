// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.features.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.annotation.StringRes
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.features.settings.model.StringSetting
import dev.lemon.lemon_emu.utils.InputHandler
import dev.lemon.lemon_emu.utils.NativeConfig

/**
 * Gamepad hotkeys: controller buttons held together run an emulator action. Each one is stored as
 * the Android key codes of its buttons (as InputHandler.getButtonIdFromEvent gives them), comma
 * separated; empty is none. They work once a Hotkey Enable button is set: every hotkey is that button
 * with its own ones, and that button is held back from the game while it is pressed.
 */
object GamepadHotkeys {
    enum class Action(val setting: StringSetting, @StringRes val titleId: Int) {
        QUICK_SAVE(StringSetting.HOTKEY_QUICK_SAVE, R.string.emulation_quick_save_state),
        QUICK_LOAD(StringSetting.HOTKEY_QUICK_LOAD, R.string.emulation_quick_load_state),
        LOAD_SLOT(StringSetting.HOTKEY_LOAD_SLOT, R.string.hotkey_load_slot),
        TOGGLE_STATS_OVERLAY(
            StringSetting.HOTKEY_TOGGLE_STATS_OVERLAY,
            R.string.hotkey_toggle_stats_overlay
        ),
        TOGGLE_SOC_OVERLAY(
            StringSetting.HOTKEY_TOGGLE_SOC_OVERLAY,
            R.string.hotkey_toggle_soc_overlay
        ),
        PAUSE_EMULATION(StringSetting.HOTKEY_PAUSE_EMULATION, R.string.emulation_pause),
        EXIT_EMULATION(StringSetting.HOTKEY_EXIT_EMULATION, R.string.emulation_exit)
    }

    /** Hotkeys come from physical controllers only (not the touch overlay). */
    fun isControllerConnected(): Boolean =
        InputDevice.getDeviceIds().any {
            InputHandler.isPhysicalGameController(InputDevice.getDevice(it))
        }

    /** The buttons of [action] besides the Hotkey Enable button. */
    fun getButtons(action: Action): Set<Int> =
        readButtons(action) - getEnableButton()

    private fun readButtons(action: Action): Set<Int> =
        action.setting.getString().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    private fun writeButtons(action: Action, buttons: Set<Int>) =
        action.setting.setString(buttons.sorted().joinToString(","))

    /** The buttons that fire [action]: its own, with the Hotkey Enable button when one is set. */
    fun getEffectiveButtons(action: Action): Set<Int> {
        val buttons = getButtons(action)
        val enable = getEnableButton()
        return if (buttons.isEmpty() || enable == 0) buttons else buttons + enable
    }

    /**
     * Sets the buttons of [action] (the Hotkey Enable button among them is left out); another
     * action with the same buttons loses them.
     */
    fun setButtons(action: Action, buttons: Set<Int>) {
        val own = buttons - getEnableButton()
        if (own.isNotEmpty()) {
            Action.entries.filter { it != action && getButtons(it) == own }
                .forEach { it.setting.setString("") }
        }
        writeButtons(action, own)
        NativeConfig.saveGlobalConfig()
    }

    /** The Hotkey Enable button, 0 for none. */
    fun getEnableButton(): Int =
        StringSetting.HOTKEY_ENABLE_BUTTON.getString().trim().toIntOrNull() ?: 0

    /**
     * Sets the Hotkey Enable button (0: none). The hotkeys keep their own buttons, so they go with
     * the new one; a hotkey whose own buttons held the new one loses it.
     */
    fun setEnableButton(keyCode: Int) {
        val old = getEnableButton()
        for (action in Action.entries) {
            val buttons = readButtons(action)
            val own = buttons - old - keyCode
            if (own != buttons) {
                writeButtons(action, own)
            }
        }
        StringSetting.HOTKEY_ENABLE_BUTTON.setString(if (keyCode == 0) "" else keyCode.toString())
        NativeConfig.saveGlobalConfig()
    }

    /** The buttons' names, the Hotkey Enable button's first. */
    fun describe(buttons: Set<Int>): String {
        val enable = getEnableButton()
        return buttons.sortedWith(compareBy({ it != enable }, { it }))
            .joinToString(" + ") { buttonName(it) }
    }

    /**
     * The hotkey of [action] as it is pressed: the Hotkey Enable button ("_" while none is set),
     * then its own buttons. Null when it has none.
     */
    fun describe(action: Action): String? {
        val buttons = getButtons(action)
        if (buttons.isEmpty()) {
            return null
        }
        val enable = getEnableButton()
        val enableName = if (enable == 0) "_" else buttonName(enable)
        return (listOf(enableName) + buttons.sorted().map { buttonName(it) }).joinToString(" + ")
    }

    private fun buttonName(keyCode: Int): String =
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
            KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
            KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
            KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
            KeyEvent.KEYCODE_BUTTON_START -> "Start"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
            KeyEvent.KEYCODE_DPAD_UP -> "D-pad Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "D-pad Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "D-pad Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "D-pad Right"
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                .removePrefix("BUTTON_")
        }
}

/**
 * Follows the buttons held on each controller and fires a hotkey on the press that completes it.
 * Its buttons are then kept from the game until they are let go; the ones held before that press
 * already reached the game, which is told they were let go. The Hotkey Enable button is held back
 * from the game instead: let go without a hotkey, the game gets a short press of it, and held on
 * its own for a moment, the game gets it from then on.
 */
class GamepadHotkeyTracker(private val sendToGame: (KeyEvent) -> Unit) {
    private class Controller {
        val held = mutableSetOf<Int>()
        val claimed = mutableSetOf<Int>()

        // The Hotkey Enable button's press while it is held: a hotkey fired with it, the game got
        // it after it was held on its own, and the task that gives it to the game then.
        var enablePress: KeyEvent? = null
        var enableUsed = false
        var enableForwarded = false
        var forwardTask: Runnable? = null
    }

    private val controllers = mutableMapOf<Int, Controller>()
    private val handler = Handler(Looper.getMainLooper())
    private var hotkeys = emptyList<Pair<GamepadHotkeys.Action, Set<Int>>>()
    private var enableButton = 0

    /**
     * Forgets the buttons held, for when their releases may not come here (the window lost focus,
     * or the in-game menu is open).
     */
    fun reset() {
        controllers.values.forEach { controller ->
            controller.forwardTask?.let { handler.removeCallbacks(it) }
        }
        controllers.clear()
    }

    /** Reads the hotkeys from the settings again. */
    fun reload() {
        reset()
        enableButton = GamepadHotkeys.getEnableButton()
        // Hotkeys need Hotkey Enable (they keep their buttons while it is not set).
        hotkeys = if (enableButton == 0) {
            emptyList()
        } else {
            GamepadHotkeys.Action.entries.map { it to GamepadHotkeys.getEffectiveButtons(it) }
                .filter { it.second.isNotEmpty() }
        }
    }

    /**
     * Follows [event] (call it for every controller key event) and returns whether it is a
     * hotkey's, to be kept from the game. A hotkey fires only when [canFire].
     */
    fun onKeyEvent(
        event: KeyEvent,
        canFire: Boolean,
        run: (GamepadHotkeys.Action) -> Unit
    ): Boolean {
        val keyCode = InputHandler.getButtonIdFromEvent(event)
        if (keyCode == 0) {
            return false
        }
        val controller = controllers.getOrPut(event.deviceId) { Controller() }
        if (enableButton != 0 && keyCode == enableButton) {
            return onEnableButtonEvent(controller, event, canFire)
        }
        val keys = controller.held
        val taken = controller.claimed
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (keyCode in taken) {
                    return true
                }
                keys.add(keyCode)
                if (!canFire || event.repeatCount > 0) {
                    if (!canFire) {
                        // Taken by something else (the menu): no short press of Hotkey Enable.
                        controller.enableUsed = true
                    }
                    return false
                }
                val (action, buttons) = hotkeys
                    .filter { keyCode in it.second && keys.containsAll(it.second) }
                    .maxByOrNull { it.second.size } ?: return false
                for (button in buttons) {
                    if (button == keyCode || button in taken) {
                        continue
                    }
                    if (button == enableButton) {
                        controller.enableUsed = true
                        controller.forwardTask?.let { handler.removeCallbacks(it) }
                        // It reached the game if held on its own, or pressed while hotkeys could
                        // not fire.
                        if (controller.enableForwarded || controller.enablePress == null) {
                            controller.enableForwarded = false
                            sendToGame(keyEvent(event, KeyEvent.ACTION_UP, button))
                        }
                    } else {
                        sendToGame(keyEvent(event, KeyEvent.ACTION_UP, button))
                        taken.add(button)
                    }
                }
                taken.add(keyCode)
                run(action)
                return true
            }

            KeyEvent.ACTION_UP -> {
                keys.remove(keyCode)
                return taken.remove(keyCode)
            }
        }
        return false
    }

    private fun onEnableButtonEvent(
        controller: Controller,
        event: KeyEvent,
        canFire: Boolean
    ): Boolean {
        val keyCode = enableButton
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    return controller.enablePress != null && !controller.enableForwarded
                }
                controller.held.add(enableButton)
                if (!canFire) {
                    return false
                }
                controller.enablePress = event
                controller.enableUsed = false
                controller.enableForwarded = false
                val task = Runnable {
                    if (controller.enablePress != null && !controller.enableUsed) {
                        controller.enableForwarded = true
                        sendToGame(keyEvent(event, KeyEvent.ACTION_DOWN, keyCode))
                    }
                }
                controller.forwardTask = task
                handler.postDelayed(task, ENABLE_HOLD_MS)
                return true
            }

            KeyEvent.ACTION_UP -> {
                controller.held.remove(enableButton)
                val press = controller.enablePress ?: return false
                controller.enablePress = null
                controller.forwardTask?.let { handler.removeCallbacks(it) }
                controller.forwardTask = null
                return when {
                    // The game has it: its release too.
                    controller.enableForwarded -> false
                    controller.enableUsed -> true
                    else -> {
                        // Let go without a hotkey: a short press, long enough for the game to see.
                        sendToGame(keyEvent(press, KeyEvent.ACTION_DOWN, keyCode))
                        handler.postDelayed(
                            { sendToGame(keyEvent(press, KeyEvent.ACTION_UP, keyCode)) },
                            ENABLE_TAP_MS
                        )
                        true
                    }
                }
            }
        }
        return false
    }

    private fun keyEvent(like: KeyEvent, action: Int, keyCode: Int): KeyEvent =
        KeyEvent(
            like.downTime,
            SystemClock.uptimeMillis(),
            action,
            keyCode,
            0,
            like.metaState,
            like.deviceId,
            0,
            like.flags,
            like.source
        )

    companion object {
        // How long Hotkey Enable is held on its own before the game gets it, and how long the
        // game sees a short press of it.
        private const val ENABLE_HOLD_MS = 400L
        private const val ENABLE_TAP_MS = 50L
    }
}
