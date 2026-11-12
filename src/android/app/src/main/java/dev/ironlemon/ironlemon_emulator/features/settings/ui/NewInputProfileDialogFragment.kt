// SPDX-FileCopyrightText: 2024 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings.ui

import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.InputProfileSetting
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonTextField

/** Names a new input profile made from the current mapping. */
class NewInputProfileDialogFragment : IronLemonComposeDialogFragment() {
    private var position = 0

    private val settingsViewModel: SettingsViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        position = requireArguments().getInt(POSITION)
    }

    @Composable
    override fun DialogContent() {
        var name by remember { mutableStateOf("") }
        IronLemonDialogCard(
            title = stringResource(R.string.enter_profile_name),
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
                        create(name)
                        dismiss()
                    }
                )
            }
        ) {
            IronLemonTextField(name, { name = it })
        }
    }

    private fun create(profileName: String) {
        val setting = settingsViewModel.clickedItem as? InputProfileSetting ?: return
        if (!setting.isProfileNameValid(profileName)) {
            Toast.makeText(requireContext(), R.string.invalid_profile_name, Toast.LENGTH_SHORT)
                .show()
            return
        }
        if (!setting.createProfile(profileName)) {
            Toast.makeText(
                requireContext(),
                R.string.profile_name_already_exists,
                Toast.LENGTH_SHORT
            ).show()
        } else {
            settingsViewModel.setAdapterItemChanged(position)
        }
    }

    companion object {
        const val TAG = "NewInputProfileDialogFragment"

        const val POSITION = "Position"

        fun newInstance(
            settingsViewModel: SettingsViewModel,
            profileSetting: InputProfileSetting,
            position: Int
        ): NewInputProfileDialogFragment {
            settingsViewModel.clickedItem = profileSetting

            val args = Bundle()
            args.putInt(POSITION, position)
            val fragment = NewInputProfileDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
