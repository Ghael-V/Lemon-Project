// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.features.settings.ui.viewholder

import android.view.View
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.databinding.ListItemSettingBinding
import dev.lemon.lemon_emu.features.settings.model.view.SettingsItem
import dev.lemon.lemon_emu.features.settings.model.view.SpinBoxSetting
import dev.lemon.lemon_emu.features.settings.ui.SettingsAdapter
import dev.lemon.lemon_emu.utils.ViewUtils.setVisible

class SpinBoxViewHolder(val binding: ListItemSettingBinding, adapter: SettingsAdapter) :
    SettingViewHolder(binding.root, adapter) {
    private lateinit var setting: SpinBoxSetting

    override fun bind(item: SettingsItem) {
        setting = item as SpinBoxSetting
        binding.textSettingName.text = setting.title
        binding.textSettingDescription.setVisible(item.description.isNotEmpty())
        binding.textSettingDescription.text = setting.description
        binding.textSettingValue.setVisible(true)
        binding.textSettingValue.text = setting.getSelectedValue().toString()

        binding.buttonClear.setVisible(setting.clearable)
        binding.buttonClear.setOnClickListener {
            adapter.onClearClick(setting, bindingAdapterPosition)
        }

        setStyle(setting.isEditable, binding)
    }
    override fun onClick(clicked: View) {
        if (setting.isEditable) {
            adapter.onSpinBoxClick(setting, bindingAdapterPosition)
        }
    }
    override fun onLongClick(clicked: View): Boolean {
        if (setting.isEditable) {
            return adapter.onLongClick(setting, bindingAdapterPosition)
        }
        return false
    }
}