// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.lemon.lemon_emu.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LifecycleOwner
import dev.lemon.lemon_emu.databinding.CardHomeOptionBinding
import dev.lemon.lemon_emu.fragments.MessageDialogFragment
import dev.lemon.lemon_emu.model.HomeSetting
import dev.lemon.lemon_emu.utils.ViewUtils.marquee
import dev.lemon.lemon_emu.utils.ViewUtils.setVisible
import dev.lemon.lemon_emu.utils.collect
import dev.lemon.lemon_emu.viewholder.AbstractViewHolder

class HomeSettingAdapter(
    private val activity: AppCompatActivity,
    private val viewLifecycle: LifecycleOwner,
    options: List<HomeSetting>
) : AbstractListAdapter<HomeSetting, HomeSettingAdapter.HomeOptionViewHolder>(options) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HomeOptionViewHolder {
        CardHomeOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            .also { return HomeOptionViewHolder(it) }
    }

    inner class HomeOptionViewHolder(val binding: CardHomeOptionBinding) :
        AbstractViewHolder<HomeSetting>(binding) {
        override fun bind(model: HomeSetting) {
            binding.optionTitle.text = activity.resources.getString(model.titleId)
            binding.optionDescription.text = activity.resources.getString(model.descriptionId)
            binding.optionIcon.setImageDrawable(
                ResourcesCompat.getDrawable(
                    activity.resources,
                    model.iconId,
                    activity.theme
                )
            )

            if (!model.isEnabled.invoke()) {
                binding.optionTitle.alpha = 0.5f
                binding.optionDescription.alpha = 0.5f
                binding.optionIcon.alpha = 0.5f
            }

            model.details.collect(viewLifecycle) { updateOptionDetails(it) }
            binding.optionDetail.marquee()

            binding.root.setOnClickListener { onClick(model) }
        }

        private fun onClick(model: HomeSetting) {
            if (model.isEnabled.invoke()) {
                model.onClick.invoke()
            } else {
                MessageDialogFragment.newInstance(
                    activity,
                    titleId = model.disabledTitleId,
                    descriptionId = model.disabledMessageId
                ).show(activity.supportFragmentManager, MessageDialogFragment.TAG)
            }
        }

        private fun updateOptionDetails(detailString: String) {
            if (detailString.isNotEmpty()) {
                binding.optionDetail.text = detailString
                binding.optionDetail.setVisible(true)
            }
        }
    }
}
