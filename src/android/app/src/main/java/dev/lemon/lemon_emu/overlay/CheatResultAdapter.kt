// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.overlay

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.lemon.lemon_emu.databinding.ItemCheatResultBinding

/**
 * One row per matched memory address in the Lemon Cheater results list.
 */
class CheatResultAdapter(private val onRowClicked: (address: Long, value: Int) -> Unit) :
    RecyclerView.Adapter<CheatResultAdapter.ViewHolder>() {
    private var results: List<Pair<Long, Int>> = emptyList()

    fun submitResults(newResults: List<Pair<Long, Int>>) {
        results = newResults
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
        holder.binding.cheatResultAddress.text = String.format("0x%016X", address)
        holder.binding.cheatResultValue.text = value.toString()
        holder.itemView.setOnClickListener { onRowClicked(address, value) }
    }

    override fun getItemCount(): Int = results.size

    class ViewHolder(val binding: ItemCheatResultBinding) :
        RecyclerView.ViewHolder(binding.root)
}
