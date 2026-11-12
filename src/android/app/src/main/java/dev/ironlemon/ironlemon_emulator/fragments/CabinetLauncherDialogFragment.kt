// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.adapters.CabinetLauncherDialogAdapter
import dev.ironlemon.ironlemon_emulator.databinding.DialogListBinding

class CabinetLauncherDialogFragment : DialogFragment() {
    private lateinit var binding: DialogListBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogListBinding.inflate(layoutInflater)
        binding.dialogList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = CabinetLauncherDialogAdapter(this@CabinetLauncherDialogFragment)
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cabinet_launcher)
            .setView(binding.root)
            .create()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return binding.root
    }
}
