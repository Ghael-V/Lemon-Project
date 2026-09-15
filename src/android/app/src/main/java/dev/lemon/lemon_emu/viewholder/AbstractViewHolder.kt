// SPDX-FileCopyrightText: 2024 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.lemon.lemon_emu.viewholder

import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import dev.lemon.lemon_emu.adapters.AbstractDiffAdapter
import dev.lemon.lemon_emu.adapters.AbstractListAdapter

/**
 * [RecyclerView.ViewHolder] meant to work together with a [AbstractDiffAdapter] or a
 * [AbstractListAdapter] so we can run [bind] on each list item without needing a manual hookup.
 */
abstract class AbstractViewHolder<Model>(binding: ViewBinding) :
    RecyclerView.ViewHolder(binding.root) {
    abstract fun bind(model: Model)
}
