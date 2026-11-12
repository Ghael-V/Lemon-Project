// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.YuzuApplication
import dev.ironlemon.ironlemon_emulator.model.AddonViewModel
import dev.ironlemon.ironlemon_emulator.ui.main.MainActivity
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonChoiceList
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard

/** Add-ons: install an update or DLC, or a mod or cheat folder. */
class ContentTypeSelectionDialogFragment : IronLemonComposeDialogFragment() {
    private val addonViewModel: AddonViewModel by activityViewModels()

    private val preferences get() =
        PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    private var selectedItem by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            selectedItem = savedInstanceState.getInt(SELECTED_ITEM)
        }
    }

    @Composable
    override fun DialogContent() {
        IronLemonDialogCard(
            title = stringResource(R.string.select_content_type),
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
                        confirm()
                        dismiss()
                    }
                )
            }
        ) {
            IronLemonChoiceList(
                options = listOf(
                    stringResource(R.string.updates_and_dlc),
                    stringResource(R.string.mods_and_cheats)
                ),
                selected = selectedItem,
                onSelect = { selectedItem = it }
            )
        }
    }

    private fun confirm() {
        when (selectedItem) {
            0 -> (requireActivity() as MainActivity).installGameUpdate.launch(arrayOf("*/*"))
            else -> {
                if (!preferences.getBoolean(MOD_NOTICE_SEEN, false)) {
                    preferences.edit().putBoolean(MOD_NOTICE_SEEN, true).apply()
                    addonViewModel.showModNoticeDialog(true)
                    return
                }
                addonViewModel.showModInstallPicker(true)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SELECTED_ITEM, selectedItem)
    }

    companion object {
        const val TAG = "ContentTypeSelectionDialogFragment"

        private const val SELECTED_ITEM = "SelectedItem"
        private const val MOD_NOTICE_SEEN = "ModNoticeSeen"
    }
}
