// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.GamesViewModel
import dev.ironlemon.ironlemon_emulator.ui.main.MainActivity
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonActionCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonCardGrid
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonIconButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonPage

/** The folders the game list is filled from: add, edit (deep scan) and remove them. */
class GameFoldersFragment : IronLemonComposeFragment() {
    private val gamesViewModel: GamesViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gamesViewModel.onOpenGameFoldersFragment()
    }

    override fun onStop() {
        super.onStop()
        gamesViewModel.onCloseGameFoldersFragment()
    }

    @Composable
    override fun Content() {
        val folders by gamesViewModel.folders.collectAsState()
        IronLemonPage(title = stringResource(R.string.game_folders), onBack = ::goBack) {
            Box(Modifier.fillMaxSize()) {
                IronLemonCardGrid(bottom = 112.dp) {
                    items(folders, key = { it.uriString }) { folder ->
                        IronLemonActionCard(
                            title = Uri.parse(folder.uriString).lastPathSegment
                                ?.substringAfterLast(':')
                                ?.substringAfterLast('/')
                                ?.ifBlank { null }
                                ?: folder.uriString,
                            description = Uri.parse(folder.uriString).path,
                            iconRes = R.drawable.ic_folder_open,
                            onClick = {
                                GameFolderPropertiesDialogFragment.newInstance(folder)
                                    .show(parentFragmentManager, GameFolderPropertiesDialogFragment.TAG)
                            }
                        ) {
                            IronLemonIconButton(
                                iconRes = R.drawable.ic_edit,
                                description = stringResource(R.string.edit),
                                onClick = {
                                    GameFolderPropertiesDialogFragment.newInstance(folder).show(
                                        parentFragmentManager,
                                        GameFolderPropertiesDialogFragment.TAG
                                    )
                                }
                            )
                            IronLemonIconButton(
                                iconRes = R.drawable.ic_delete,
                                description = stringResource(R.string.delete),
                                onClick = { gamesViewModel.removeFolder(folder) }
                            )
                        }
                    }
                }
                IronLemonButton(
                    text = stringResource(R.string.add_games),
                    iconRes = R.drawable.ic_add,
                    primary = true,
                    height = 56.dp,
                    onClick = {
                        (requireActivity() as MainActivity).getGamesDirectory
                            .launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
                )
            }
        }
    }
}
