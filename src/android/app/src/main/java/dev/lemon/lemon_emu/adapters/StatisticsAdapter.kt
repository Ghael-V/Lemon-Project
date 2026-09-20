// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.navigation.findNavController
import dev.lemon.lemon_emu.HomeNavigationDirections
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.databinding.ListItemGameStatBinding
import dev.lemon.lemon_emu.model.GameStatEntry
import dev.lemon.lemon_emu.utils.GameIconUtils
import dev.lemon.lemon_emu.utils.GameStatsUtils
import dev.lemon.lemon_emu.viewholder.AbstractViewHolder

class StatisticsAdapter(entries: List<GameStatEntry>) :
    AbstractListAdapter<GameStatEntry, StatisticsAdapter.StatViewHolder>(entries) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StatViewHolder {
        ListItemGameStatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            .also { return StatViewHolder(it) }
    }

    inner class StatViewHolder(private val binding: ListItemGameStatBinding) :
        AbstractViewHolder<GameStatEntry>(binding) {
        override fun bind(model: GameStatEntry) {
            val context = binding.root.context

            GameIconUtils.loadGameIcon(model.game, binding.imageGameIcon)
            binding.textGameTitle.text = model.game.title

            binding.textGameStats.text = if (model.lastPlayedMillis <= 0L) {
                context.getString(R.string.game_never_played)
            } else {
                GameStatsUtils.formatFullSummary(
                    context,
                    model.playTimeSeconds,
                    model.lastPlayedMillis,
                    model.sessionCount
                )
            }

            binding.root.setOnClickListener {
                val action = HomeNavigationDirections
                    .actionGlobalPerGamePropertiesFragment(model.game)
                binding.root.findNavController().navigate(action)
            }
        }
    }
}
