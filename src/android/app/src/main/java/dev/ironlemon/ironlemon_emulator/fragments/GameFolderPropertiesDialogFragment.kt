// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.GameDir
import dev.ironlemon.ironlemon_emulator.model.GamesViewModel
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonSwitchRow
import dev.ironlemon.ironlemon_emulator.utils.NativeConfig
import dev.ironlemon.ironlemon_emulator.utils.SerializableHelper.parcelable

/** A game folder's options: whether its subfolders are searched too (deep scan). */
class GameFolderPropertiesDialogFragment : IronLemonComposeDialogFragment() {
    private val gamesViewModel: GamesViewModel by activityViewModels()

    private val gameDir get() = requireArguments().parcelable<GameDir>(GAME_DIR)!!

    private var deepScan by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepScan = savedInstanceState?.getBoolean(DEEP_SCAN) ?: gameDir.deepScan
    }

    @Composable
    override fun DialogContent() {
        IronLemonDialogCard(
            title = stringResource(R.string.game_folder_properties),
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
                        val folderIndex = gamesViewModel.folders.value.indexOf(gameDir)
                        if (folderIndex != -1) {
                            gamesViewModel.folders.value[folderIndex].deepScan = deepScan
                            gamesViewModel.updateGameDirs()
                        }
                        dismiss()
                    }
                )
            }
        ) {
            IronLemonSwitchRow(stringResource(R.string.deep_scan), deepScan) { deepScan = it }
        }
    }

    override fun onStop() {
        super.onStop()
        NativeConfig.saveGlobalConfig()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(DEEP_SCAN, deepScan)
    }

    companion object {
        const val TAG = "GameFolderPropertiesDialogFragment"

        private const val GAME_DIR = "GameDir"

        private const val DEEP_SCAN = "DeepScan"

        fun newInstance(gameDir: GameDir): GameFolderPropertiesDialogFragment {
            val args = Bundle()
            args.putParcelable(GAME_DIR, gameDir)
            val fragment = GameFolderPropertiesDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
