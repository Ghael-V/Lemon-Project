// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.adapters

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import dev.ironlemon.ironlemon_emulator.databinding.CardFolderBinding
import dev.ironlemon.ironlemon_emulator.fragments.GameFolderPropertiesDialogFragment
import dev.ironlemon.ironlemon_emulator.model.GameDir
import dev.ironlemon.ironlemon_emulator.model.GamesViewModel
import dev.ironlemon.ironlemon_emulator.utils.ViewUtils.marquee
import dev.ironlemon.ironlemon_emulator.viewholder.AbstractViewHolder

class FolderAdapter(val activity: FragmentActivity, val gamesViewModel: GamesViewModel) :
    AbstractDiffAdapter<GameDir, FolderAdapter.FolderViewHolder>() {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): FolderAdapter.FolderViewHolder {
        CardFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            .also { return FolderViewHolder(it) }
    }

    inner class FolderViewHolder(val binding: CardFolderBinding) :
        AbstractViewHolder<GameDir>(binding) {
        override fun bind(model: GameDir) {
            binding.apply {
                path.text = Uri.parse(model.uriString).path
                path.marquee()

                buttonEdit.setOnClickListener {
                    GameFolderPropertiesDialogFragment.newInstance(model)
                        .show(
                            activity.supportFragmentManager,
                            GameFolderPropertiesDialogFragment.TAG
                        )
                }

                buttonDelete.setOnClickListener {
                    gamesViewModel.removeFolder(model)
                }
            }
        }
    }
}
