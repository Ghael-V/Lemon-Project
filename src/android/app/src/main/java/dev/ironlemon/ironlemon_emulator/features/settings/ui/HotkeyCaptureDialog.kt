// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings.ui

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.input.GamepadHotkeys
import dev.ironlemon.ironlemon_emulator.utils.InputHandler

/**
 * Records a gamepad hotkey, or the Hotkey Enable button: the buttons held together, taken once
 * they are all let go. Every controller button goes to it, so it is closed by touch (or the
 * device's Back key).
 */
object HotkeyCaptureDialog {
    fun show(activity: FragmentActivity, action: GamepadHotkeys.Action, onSet: () -> Unit) {
        val enable = GamepadHotkeys.getEnableButton()
        var message = activity.getString(R.string.gamepad_hotkey_capture)
        if (enable != 0) {
            message += "\n\n" + activity.getString(
                R.string.hotkey_enable_part,
                GamepadHotkeys.describe(setOf(enable))
            )
        }
        capture(activity, action.titleId, message, onClear = {
            GamepadHotkeys.setButtons(action, emptySet())
            onSet()
        }) { buttons ->
            if (enable != 0 && (buttons - enable).isEmpty()) {
                return@capture activity.getString(
                    R.string.hotkey_needs_another_button,
                    GamepadHotkeys.describe(setOf(enable))
                )
            }
            GamepadHotkeys.setButtons(action, buttons)
            onSet()
            null
        }
    }

    fun showEnableButton(activity: FragmentActivity, onSet: () -> Unit) {
        capture(
            activity,
            R.string.hotkey_enable_button,
            activity.getString(R.string.hotkey_enable_button_capture),
            onClear = {
                GamepadHotkeys.setEnableButton(0)
                onSet()
            }
        ) { buttons ->
            if (buttons.size != 1) {
                return@capture activity.getString(R.string.hotkey_press_one_button)
            }
            GamepadHotkeys.setEnableButton(buttons.first())
            onSet()
            null
        }
    }

    /**
     * Shows the dialog; [take] gets the buttons once they are let go and returns null when it took
     * them (the dialog closes), or a message to show while recording again.
     */
    private fun capture(
        activity: FragmentActivity,
        @StringRes titleId: Int,
        message: String,
        onClear: () -> Unit,
        take: (Set<Int>) -> String?
    ) {
        val held = mutableSetOf<Int>()
        val buttons = mutableSetOf<Int>()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(titleId)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.clear) { _, _ -> onClear() }
            .create()
        dialog.setOnKeyListener { _, _, event ->
            onKeyEvent(dialog, event, held, buttons, take)
        }
        dialog.show()
    }

    private fun onKeyEvent(
        dialog: AlertDialog,
        event: KeyEvent,
        held: MutableSet<Int>,
        buttons: MutableSet<Int>,
        take: (Set<Int>) -> String?
    ): Boolean {
        // Back still closes the dialog; the Guide button always toggles the in-game menu.
        if (event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE ||
            !InputHandler.isPhysicalGameController(event.device)
        ) {
            return false
        }
        // Some controllers send their D-pad as key code 0 with a scan code.
        val keyCode = InputHandler.getButtonIdFromEvent(event)
        if (keyCode == 0) {
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                held.add(keyCode)
                buttons.add(keyCode)
                dialog.setMessage(GamepadHotkeys.describe(buttons))
            }

            KeyEvent.ACTION_UP -> {
                held.remove(keyCode)
                // The button that opened the dialog is let go here too, with nothing recorded yet.
                if (held.isEmpty() && buttons.isNotEmpty()) {
                    val problem = take(buttons.toSet())
                    if (problem == null) {
                        dialog.dismiss()
                    } else {
                        buttons.clear()
                        dialog.setMessage(problem)
                    }
                }
            }
        }
        return true
    }
}
