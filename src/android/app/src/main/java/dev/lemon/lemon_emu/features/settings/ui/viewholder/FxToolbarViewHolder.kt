// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.features.settings.ui.viewholder

import android.content.res.ColorStateList
import android.view.View
import com.google.android.material.color.MaterialColors
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.databinding.ListItemSettingFxToolbarBinding
import dev.lemon.lemon_emu.features.settings.model.view.FxToolbarSetting
import dev.lemon.lemon_emu.features.settings.model.view.SettingsItem
import dev.lemon.lemon_emu.features.settings.ui.SettingsAdapter

class FxToolbarViewHolder(
    val binding: ListItemSettingFxToolbarBinding,
    adapter: SettingsAdapter
) : SettingViewHolder(binding.root, adapter) {
    private lateinit var setting: FxToolbarSetting

    override fun bind(item: SettingsItem) {
        setting = item as FxToolbarSetting

        binding.fxAdd.setText(setting.addLabelId)
        var addIcon = R.drawable.ic_add
        if (setting.listOpen) {
            addIcon = R.drawable.ic_clear
        }
        binding.fxAdd.setIconResource(addIcon)
        binding.fxAdd.setOnClickListener { setting.onAdd.invoke() }

        binding.fxPresets.text = setting.presetLabel
        binding.fxPresets.setOnClickListener { setting.onPresets.invoke() }

        binding.fxCreatePreset.isEnabled = setting.hasEffects
        binding.fxCreatePreset.setOnClickListener {
            adapter.onStringInputClick(setting.createPreset, bindingAdapterPosition)
        }

        val removeBackground = MaterialColors.getColor(
            binding.fxRemoveAll,
            com.google.android.material.R.attr.colorErrorContainer
        )
        val removeForeground = MaterialColors.getColor(
            binding.fxRemoveAll,
            com.google.android.material.R.attr.colorOnErrorContainer
        )
        binding.fxRemoveAll.backgroundTintList = ColorStateList.valueOf(removeBackground)
        binding.fxRemoveAll.iconTint = ColorStateList.valueOf(removeForeground)
        binding.fxRemoveAll.isEnabled = setting.hasEffects
        binding.fxRemoveAll.setOnClickListener { setting.onRemoveAll.invoke() }
    }

    override fun onClick(clicked: View) {}

    override fun onLongClick(clicked: View): Boolean = true
}
