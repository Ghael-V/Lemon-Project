// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.settings.ui.SettingsActivity
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText

class ResetSettingsDialogFragment : IronLemonComposeDialogFragment() {
    @Composable
    override fun DialogContent() {
        IronLemonDialogCard(
            title = stringResource(R.string.reset_all_settings),
            buttons = {
                IronLemonButton(
                    text = stringResource(android.R.string.cancel),
                    height = 44.dp,
                    onClick = ::dismiss
                )
                IronLemonButton(
                    text = stringResource(android.R.string.ok),
                    primary = true,
                    height = 44.dp,
                    onClick = {
                        (requireActivity() as SettingsActivity).onSettingsReset()
                        dismiss()
                    }
                )
            }
        ) {
            IronLemonDialogText(stringResource(R.string.reset_all_settings_description))
        }
    }

    companion object {
        const val TAG = "ResetSettingsDialogFragment"
    }
}
