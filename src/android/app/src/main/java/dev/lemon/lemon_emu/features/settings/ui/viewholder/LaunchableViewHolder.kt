// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.features.settings.ui.viewholder

import android.view.View
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.databinding.ListItemSettingBinding
import dev.lemon.lemon_emu.features.settings.model.view.LaunchableSetting
import dev.lemon.lemon_emu.features.settings.model.view.SettingsItem
import dev.lemon.lemon_emu.features.settings.ui.SettingsAdapter
import dev.lemon.lemon_emu.utils.ViewUtils.setVisible

class LaunchableViewHolder(val binding: ListItemSettingBinding, adapter: SettingsAdapter) :
    SettingViewHolder(binding.root, adapter) {
    private lateinit var setting: LaunchableSetting

    override fun bind(item: SettingsItem) {
        setting = item as LaunchableSetting

        binding.textSettingName.text = setting.title
        binding.textSettingDescription.setVisible(setting.description.isNotEmpty())
        binding.textSettingDescription.text = setting.description

        binding.textSettingValue.setVisible(true)
        binding.textSettingValue.text = ""
        binding.textSettingValue.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, R.drawable.ic_arrow_forward, 0
        )

        binding.buttonClear.setVisible(false)
    }

    override fun onClick(clicked: View) {
        adapter.onLaunchableClick(setting)
    }

    override fun onLongClick(clicked: View): Boolean {
        // no-op
        return true
    }
}
