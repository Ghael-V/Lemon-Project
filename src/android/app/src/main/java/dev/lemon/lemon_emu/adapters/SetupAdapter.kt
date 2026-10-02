// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.lemon.lemon_emu.adapters

import android.text.Html
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import dev.lemon.lemon_emu.databinding.PageSetupBinding
import dev.lemon.lemon_emu.databinding.PageSetupModernBinding
import dev.lemon.lemon_emu.ui.modern.ModernSetupFinish
import dev.lemon.lemon_emu.ui.modern.ModernSetupPage
import dev.lemon.lemon_emu.ui.modern.SetupSummaryItem
import dev.lemon.lemon_emu.ui.modern.SetupCardData
import dev.lemon.lemon_emu.ui.modern.UiMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.lemon.lemon_emu.model.PageState
import dev.lemon.lemon_emu.model.SetupCallback
import dev.lemon.lemon_emu.model.SetupPage
import dev.lemon.lemon_emu.utils.ViewUtils
import dev.lemon.lemon_emu.viewholder.AbstractViewHolder
import android.content.res.ColorStateList
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.model.ButtonState

class SetupAdapter(val activity: AppCompatActivity, pages: List<SetupPage>) :
    AbstractListAdapter<SetupPage, AbstractViewHolder<SetupPage>>(pages) {
    // The redesigned interface shows each step as cards; the classic one keeps its buttons.
    private val modern = UiMode.isModern(activity)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AbstractViewHolder<SetupPage> {
        val inflater = LayoutInflater.from(parent.context)
        if (modern) {
            return ModernSetupPageViewHolder(PageSetupModernBinding.inflate(inflater, parent, false))
        }
        return SetupPageViewHolder(PageSetupBinding.inflate(inflater, parent, false))
    }

    // Bumped whenever something may have been completed or a step comes into view, so every card
    // (and the final recap) re-reads its state instead of showing what it saw when it was created.
    private var refresh by mutableIntStateOf(0)

    fun refreshStates() {
        refresh++
    }

    inner class ModernSetupPageViewHolder(val binding: PageSetupModernBinding) :
        AbstractViewHolder<SetupPage>(binding), SetupCallback {
        override fun bind(model: SetupPage) {
            val position = bindingAdapterPosition.coerceAtLeast(0)
            val isLast = position == itemCount - 1
            binding.composePage.setContent {
                if (isLast) {
                    // The closing step: a recap of everything the earlier steps could set up, and
                    // the button that finishes setup.
                    val version = refresh
                    val summary = remember(version) {
                        currentList.dropLast(1)
                            .flatMap { it.pageButtons ?: emptyList() }
                            .filter { it.buttonState.invoke() != ButtonState.BUTTON_ACTION_UNDEFINED }
                            .map {
                                SetupSummaryItem(
                                    activity.getString(it.titleId),
                                    it.buttonState.invoke() == ButtonState.BUTTON_ACTION_COMPLETE
                                )
                            }
                    }
                    val action = model.pageButtons?.firstOrNull()
                    ModernSetupFinish(
                        title = activity.getString(model.titleId),
                        description = Html.fromHtml(activity.getString(model.descriptionId), 0)
                            .toString().trim(),
                        summary = summary,
                        actionText = action?.let { activity.getString(it.titleId) } ?: "",
                        onAction = { action?.buttonAction?.invoke(this@ModernSetupPageViewHolder) }
                    )
                    return@setContent
                }
                // Reading `refresh` here is what makes a completed step update on screen.
                val version = refresh
                val cards = remember(version) {
                    (model.pageButtons ?: emptyList()).map { button ->
                        val state = button.buttonState.invoke()
                        SetupCardData(
                            iconRes = button.iconId,
                            title = activity.getString(button.titleId),
                            subtitle = if (button.descriptionId != 0) {
                                Html.fromHtml(activity.getString(button.descriptionId), 0)
                                    .toString().trim()
                            } else {
                                ""
                            },
                            done = state == ButtonState.BUTTON_ACTION_COMPLETE,
                            required = button.isUnskippable,
                            isAction = state == ButtonState.BUTTON_ACTION_UNDEFINED,
                            onClick = { button.buttonAction.invoke(this@ModernSetupPageViewHolder) }
                        )
                    }
                }
                val pageDone = remember(version) { model.pageSteps.invoke() == PageState.COMPLETE }
                ModernSetupPage(
                    stepNumber = position + 1,
                    stepCount = itemCount,
                    iconRes = model.iconId,
                    title = activity.getString(model.titleId),
                    description = Html.fromHtml(activity.getString(model.descriptionId), 0)
                        .toString().trim(),
                    cards = cards,
                    pageDone = pageDone
                )
            }
        }

        override fun onStepCompleted(pageButtonId: Int, pageFullyCompleted: Boolean) {
            refresh++
        }
    }

    inner class SetupPageViewHolder(val binding: PageSetupBinding) :
        AbstractViewHolder<SetupPage>(binding), SetupCallback {
        override fun bind(model: SetupPage) {
            if (model.pageSteps.invoke() == PageState.COMPLETE) {
                onStepCompleted(0, pageFullyCompleted = true)
            }

            if (model.pageButtons != null && model.pageSteps.invoke() != PageState.COMPLETE) {
                for (pageButton in model.pageButtons) {
                    val pageButtonView = LayoutInflater.from(activity)
                        .inflate(
                            R.layout.page_button,
                            binding.pageButtonContainer,
                            false
                        ) as MaterialButton

                    pageButtonView.apply {
                        id = pageButton.titleId
                        icon = ResourcesCompat.getDrawable(
                            activity.resources,
                            pageButton.iconId,
                            activity.theme
                        )
                        text = activity.resources.getString(pageButton.titleId)
                    }

                    pageButtonView.setOnClickListener {
                        pageButton.buttonAction.invoke(this@SetupPageViewHolder)
                    }

                    binding.pageButtonContainer.addView(pageButtonView)

                    // Disable buton add if its already completed
                    if (pageButton.buttonState.invoke() == ButtonState.BUTTON_ACTION_COMPLETE) {
                        onStepCompleted(pageButton.titleId, pageFullyCompleted = false)
                    }
                }
            }

            binding.icon.setImageDrawable(
                ResourcesCompat.getDrawable(
                    activity.resources,
                    model.iconId,
                    activity.theme
                )
            )
            binding.textTitle.text = activity.resources.getString(model.titleId)
            binding.textDescription.text =
                Html.fromHtml(activity.resources.getString(model.descriptionId), 0)
        }

        override fun onStepCompleted(pageButtonId: Int, pageFullyCompleted: Boolean) {
            val button = binding.pageButtonContainer.findViewById<MaterialButton>(pageButtonId)

            if (pageFullyCompleted) {
                ViewUtils.hideView(binding.pageButtonContainer, 200)
                ViewUtils.showView(binding.textConfirmation, 200)
            }

            if (button != null) {
                button.isEnabled = false
                button.animate()
                    .alpha(0.38f)
                    .setDuration(200)
                    .start()
                button.setTextColor(button.context.getColor(com.google.android.material.R.color.material_on_surface_disabled))
                button.iconTint =
                    ColorStateList.valueOf(button.context.getColor(com.google.android.material.R.color.material_on_surface_disabled))
            }
        }
    }
}
