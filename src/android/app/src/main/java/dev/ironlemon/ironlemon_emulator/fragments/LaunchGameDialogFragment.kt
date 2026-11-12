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
import androidx.navigation.fragment.findNavController
import dev.ironlemon.ironlemon_emulator.HomeNavigationDirections
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonChoiceList
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.utils.SerializableHelper.parcelable

/** Start a game with the global settings or with its own. */
class LaunchGameDialogFragment : IronLemonComposeDialogFragment() {
    private var selectedItem by mutableIntStateOf(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            selectedItem = savedInstanceState.getInt(SELECTED_ITEM)
        }
    }

    @Composable
    override fun DialogContent() {
        IronLemonDialogCard(
            title = stringResource(R.string.launch_options),
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
                        val game = requireArguments().parcelable<Game>(GAME)
                        val action = HomeNavigationDirections
                            .actionGlobalEmulationActivity(game, selectedItem != 0)
                        requireParentFragment().findNavController().navigate(action)
                        dismiss()
                    }
                )
            }
        ) {
            IronLemonChoiceList(
                options = listOf(stringResource(R.string.global), stringResource(R.string.custom)),
                selected = selectedItem,
                onSelect = { selectedItem = it }
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SELECTED_ITEM, selectedItem)
    }

    companion object {
        const val TAG = "LaunchGameDialogFragment"

        const val GAME = "Game"
        const val SELECTED_ITEM = "SelectedItem"

        fun newInstance(game: Game): LaunchGameDialogFragment {
            val args = Bundle()
            args.putParcelable(GAME, game)
            val fragment = LaunchGameDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
