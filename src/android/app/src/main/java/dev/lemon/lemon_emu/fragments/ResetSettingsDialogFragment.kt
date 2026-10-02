// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.lemon.lemon_emu.fragments

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.features.settings.ui.SettingsActivity
import dev.lemon.lemon_emu.utils.SettingsReset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reset confirmation. Opened from the game's own settings it resets just that game; opened from anywhere
 * else it resets every setting, with the choice of also deleting the games' custom settings.
 */
class ResetSettingsDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val settingsActivity = activity as? SettingsActivity

        if (settingsActivity?.isPerGameSettings == true) {
            return MaterialAlertDialogBuilder(context)
                .setTitle(R.string.reset_game_settings_question)
                .setMessage(R.string.reset_game_settings_dialog_description)
                .setPositiveButton(android.R.string.ok) { _, _ -> settingsActivity.onSettingsReset() }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }

        val view = layoutInflater.inflate(R.layout.dialog_reset_settings, null)
        val deleteGameSettings = view.findViewById<MaterialCheckBox>(R.id.check_delete_game_settings)
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.reset_all_settings)
            .setView(view)
            .setPositiveButton(R.string.reset_everything_confirm) { _, _ ->
                resetEverything(deleteGameSettings.isChecked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun resetEverything(deleteGameSettings: Boolean) {
        val appContext = requireContext().applicationContext
        val host = activity
        // The dialog closes right after the tap, so the work must not depend on it being alive.
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { SettingsReset.resetAll(deleteGameSettings) }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    appContext,
                    if (result.isSuccess) R.string.settings_reset_everything else R.string.reset_everything_failed,
                    Toast.LENGTH_LONG
                ).show()
                if (result.isSuccess && host is SettingsActivity) {
                    host.finish()
                }
            }
        }
    }

    companion object {
        const val TAG = "ResetSettingsDialogFragment"
    }
}
