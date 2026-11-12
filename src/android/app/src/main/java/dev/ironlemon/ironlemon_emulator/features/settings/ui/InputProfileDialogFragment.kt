// SPDX-FileCopyrightText: 2024 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.InputProfileSetting
import dev.ironlemon.ironlemon_emulator.fragments.MessageDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonActionCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonIconButton
import dev.ironlemon.ironlemon_emulator.utils.collect

/** A player's input profiles: make a new one, or load, save over or delete an existing one. */
class InputProfileDialogFragment : IronLemonComposeDialogFragment() {
    private var position = 0

    private val settingsViewModel: SettingsViewModel by activityViewModels()

    private val setting get() = settingsViewModel.clickedItem as? InputProfileSetting

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        position = requireArguments().getInt(POSITION)
        if (setting == null) dismiss()
    }

    @Composable
    override fun DialogContent() {
        val setting = setting ?: return
        IronLemonDialogCard(title = stringResource(R.string.profile)) {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IronLemonActionCard(
                    title = stringResource(R.string.create_new_profile),
                    description = null,
                    iconRes = R.drawable.ic_new_label,
                    onClick = {
                        NewInputProfileDialogFragment.newInstance(settingsViewModel, setting, position)
                            .show(parentFragmentManager, NewInputProfileDialogFragment.TAG)
                        dismiss()
                    }
                )
                setting.getProfileNames().forEach { name ->
                    IronLemonActionCard(title = name, description = null) {
                        IronLemonIconButton(
                            iconRes = R.drawable.ic_import,
                            description = stringResource(R.string.load),
                            onClick = {
                                if (!setting.loadProfile(name)) {
                                    toast(R.string.failed_to_load_profile)
                                }
                                done()
                            }
                        )
                        IronLemonIconButton(
                            iconRes = R.drawable.ic_save,
                            description = stringResource(R.string.save),
                            onClick = {
                                if (!setting.saveProfile(name)) {
                                    toast(R.string.failed_to_save_profile)
                                }
                                done()
                            }
                        )
                        IronLemonIconButton(
                            iconRes = R.drawable.ic_delete,
                            description = stringResource(R.string.delete),
                            onClick = { settingsViewModel.setShouldShowDeleteProfileDialog(name) }
                        )
                    }
                }
            }
        }
    }

    private fun toast(message: Int) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    private fun done() {
        settingsViewModel.setReloadListAndNotifyDataset(true)
        dismiss()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settingsViewModel.shouldShowDeleteProfileDialog.collect(viewLifecycleOwner) {
            val setting = setting ?: return@collect
            if (it.isNotEmpty()) {
                MessageDialogFragment.newInstance(
                    activity = requireActivity(),
                    titleId = R.string.delete_input_profile,
                    descriptionId = R.string.delete_input_profile_description,
                    positiveAction = {
                        setting.deleteProfile(it)
                        settingsViewModel.setReloadListAndNotifyDataset(true)
                    },
                    negativeAction = {},
                    negativeButtonTitleId = android.R.string.cancel
                ).show(parentFragmentManager, MessageDialogFragment.TAG)
                settingsViewModel.setShouldShowDeleteProfileDialog("")
                dismiss()
            }
        }
    }

    companion object {
        const val TAG = "InputProfileDialogFragment"

        const val POSITION = "Position"

        fun newInstance(
            settingsViewModel: SettingsViewModel,
            profileSetting: InputProfileSetting,
            position: Int
        ): InputProfileDialogFragment {
            settingsViewModel.clickedItem = profileSetting

            val args = Bundle()
            args.putInt(POSITION, position)
            val fragment = InputProfileDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
