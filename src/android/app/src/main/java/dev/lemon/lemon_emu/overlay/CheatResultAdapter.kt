// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.overlay

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.databinding.ItemCheatResultBinding

/**
 * One row per matched memory address in the Lemon Cheater results list (or, when the
 * "Frozen" view is selected, one row per currently-frozen address).
 */
class CheatResultAdapter(
    private val onRowClicked: (address: Long, value: Int) -> Unit,
    private val onFreezeToggled: (address: Long, value: Int, currentlyFrozen: Boolean) -> Unit
) : RecyclerView.Adapter<CheatResultAdapter.ViewHolder>() {
    private var results: List<Pair<Long, Int>> = emptyList()
    private var frozenAddresses: Set<Long> = emptySet()

    fun submitResults(newResults: List<Pair<Long, Int>>, frozen: Set<Long>) {
        results = newResults
        frozenAddresses = frozen
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCheatResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (address, value) = results[position]
        val isFrozen = frozenAddresses.contains(address)

        holder.binding.cheatResultAddress.text = String.format("0x%016X", address)
        holder.binding.cheatResultValue.text = value.toString()
        holder.itemView.setOnClickListener { onRowClicked(address, value) }

        holder.binding.cheatResultFreeze.apply {
            setImageResource(if (isFrozen) R.drawable.ic_lock else R.drawable.ic_unlock)
            contentDescription = context.getString(
                if (isFrozen) R.string.lemon_cheater_unfreeze else R.string.lemon_cheater_freeze
            )
            setOnClickListener { onFreezeToggled(address, value, isFrozen) }
        }
    }

    override fun getItemCount(): Int = results.size

    class ViewHolder(val binding: ItemCheatResultBinding) :
        RecyclerView.ViewHolder(binding.root)
}
