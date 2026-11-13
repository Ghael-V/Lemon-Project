// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings.ui.viewholder

import android.view.View
import androidx.core.content.res.ResourcesCompat
import dev.ironlemon.ironlemon_emulator.databinding.ListItemSettingBinding
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.RunnableSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.SettingsItem
import dev.ironlemon.ironlemon_emulator.features.settings.ui.SettingsAdapter
import dev.ironlemon.ironlemon_emulator.utils.ViewUtils.setVisible

class RunnableViewHolder(val binding: ListItemSettingBinding, adapter: SettingsAdapter) :
    SettingViewHolder(binding.root, adapter) {
    private lateinit var setting: RunnableSetting

    override fun bind(item: SettingsItem) {
        setting = item as RunnableSetting
        binding.icon.setVisible(setting.iconId != 0)
        if (setting.iconId != 0) {
            binding.icon.setImageDrawable(
                ResourcesCompat.getDrawable(
                    binding.icon.resources,
                    setting.iconId,
                    binding.icon.context.theme
                )
            )
        }

        binding.textSettingName.text = setting.title
        binding.textSettingDescription.setVisible(setting.description.isNotEmpty())
        binding.textSettingDescription.text = item.description
        binding.textSettingValue.setVisible(false)
        binding.buttonClear.setVisible(false)

        val trailingSwitch = setting.trailingSwitch
        binding.trailingSwitchArea.setVisible(trailingSwitch != null)
        if (trailingSwitch != null) {
            binding.textTrailingSwitch.text = trailingSwitch.title
            binding.trailingSwitch.setOnCheckedChangeListener(null)
            binding.trailingSwitch.isChecked =
                trailingSwitch.getIsChecked(trailingSwitch.needsRuntimeGlobal)
            binding.trailingSwitch.isEnabled = setting.isEditable
            binding.textTrailingSwitch.alpha = if (setting.isEditable) 1.0f else 0.5f
            binding.trailingSwitch.setOnCheckedChangeListener { _, checked ->
                adapter.onBooleanClick(trailingSwitch, checked, bindingAdapterPosition)
            }
        }

        setStyle(setting.isEditable, binding)
    }

    override fun onClick(clicked: View) {
        if (setting.isRunnable) {
            setting.runnable.invoke()
        }
    }

    override fun onLongClick(clicked: View): Boolean {
        // no-op
        return true
    }
}
