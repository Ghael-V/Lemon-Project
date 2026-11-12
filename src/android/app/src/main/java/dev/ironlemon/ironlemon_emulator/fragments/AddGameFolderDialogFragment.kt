// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.GameDir
import dev.ironlemon.ironlemon_emulator.model.GamesViewModel
import dev.ironlemon.ironlemon_emulator.model.HomeViewModel
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonSwitchRow

/** A folder was picked to add to the game list; choose whether to search its subfolders too. */
class AddGameFolderDialogFragment : IronLemonComposeDialogFragment() {
    private val homeViewModel: HomeViewModel by activityViewModels()
    private val gamesViewModel: GamesViewModel by activityViewModels()

    private var deepScan by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requireArguments().getString(FOLDER_URI_STRING) == null) {
            dismiss()
        }
        deepScan = savedInstanceState?.getBoolean(DEEP_SCAN) ?: false
    }

    @Composable
    override fun DialogContent() {
        val folderUriString = requireArguments().getString(FOLDER_URI_STRING) ?: return
        IronLemonDialogCard(
            title = stringResource(R.string.add_game_folder),
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
                        homeViewModel.setGamesDirSelected(true)
                        gamesViewModel.addFolder(GameDir(folderUriString, deepScan))
                        dismiss()
                    }
                )
            }
        ) {
            IronLemonDialogText(
                Uri.parse(folderUriString).path?.removePrefix("/tree/") ?: folderUriString
            )
            Spacer(Modifier.height(16.dp))
            IronLemonSwitchRow(stringResource(R.string.deep_scan), deepScan) { deepScan = it }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(DEEP_SCAN, deepScan)
    }

    companion object {
        const val TAG = "AddGameFolderDialogFragment"

        private const val FOLDER_URI_STRING = "FolderUriString"
        private const val DEEP_SCAN = "DeepScan"

        fun newInstance(folderUriString: String): AddGameFolderDialogFragment {
            val args = Bundle()
            args.putString(FOLDER_URI_STRING, folderUriString)
            val fragment = AddGameFolderDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
