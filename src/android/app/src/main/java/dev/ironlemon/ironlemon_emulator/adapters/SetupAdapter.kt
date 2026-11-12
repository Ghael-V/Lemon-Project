// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.adapters

import android.text.Html
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import dev.ironlemon.ironlemon_emulator.model.HomeViewModel
import dev.ironlemon.ironlemon_emulator.model.SetupCallback
import dev.ironlemon.ironlemon_emulator.model.SetupPage
import dev.ironlemon.ironlemon_emulator.model.StepState
import dev.ironlemon.ironlemon_emulator.ui.modern.ModernSetupFinish
import dev.ironlemon.ironlemon_emulator.ui.modern.ModernSetupPage
import dev.ironlemon.ironlemon_emulator.ui.modern.ModernWelcomeScreen
import dev.ironlemon.ironlemon_emulator.ui.modern.SetupCardData
import dev.ironlemon.ironlemon_emulator.ui.modern.SetupSummaryItem

/**
 * The first-run setup pages, drawn with Iron Lemon's Compose screens: a welcome page, a page per
 * step, and a summary at the end. Each step keeps yuzu's own action and completion check.
 */
class SetupAdapter(val activity: AppCompatActivity, private val pages: List<SetupPage>) :
    RecyclerView.Adapter<SetupAdapter.SetupPageViewHolder>() {

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SetupPageViewHolder {
        val view = ComposeView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        return SetupPageViewHolder(view)
    }

    override fun onBindViewHolder(holder: SetupPageViewHolder, position: Int) =
        holder.bind(pages[position], position)

    inner class SetupPageViewHolder(private val view: ComposeView) :
        RecyclerView.ViewHolder(view), SetupCallback {
        private val tick = mutableIntStateOf(0)

        fun bind(model: SetupPage, position: Int) {
            val resources = activity.resources
            val title = resources.getString(model.titleId)
            val description =
                Html.fromHtml(resources.getString(model.descriptionId), 0).toString()
            val action = { model.buttonAction.invoke(this) }
            view.setContent {
                @Suppress("UNUSED_VARIABLE")
                val refresh = tick.intValue
                when (position) {
                    0 -> ModernWelcomeScreen(onStart = action)
                    pages.lastIndex -> ModernSetupFinish(
                        title = title,
                        description = description,
                        summary = pages.drop(1).dropLast(1)
                            .filter { it.stepCompleted.invoke() != StepState.UNDEFINED }
                            .map {
                                SetupSummaryItem(
                                    resources.getString(it.titleId),
                                    it.stepCompleted.invoke() == StepState.COMPLETE
                                )
                            },
                        actionText = resources.getString(model.buttonTextId),
                        onAction = action
                    )
                    else -> {
                        val done = model.stepCompleted.invoke() == StepState.COMPLETE
                        ModernSetupPage(
                            stepNumber = position,
                            stepCount = pages.size - 2,
                            iconRes = model.iconId,
                            title = title,
                            description = description,
                            cards = listOf(
                                SetupCardData(
                                    iconRes = if (model.buttonIconId != 0) {
                                        model.buttonIconId
                                    } else {
                                        model.iconId
                                    },
                                    title = resources.getString(model.buttonTextId),
                                    subtitle = "",
                                    done = done,
                                    required = model.hasWarning,
                                    isAction = model.stepCompleted.invoke() == StepState.UNDEFINED,
                                    onClick = action
                                )
                            ),
                            pageDone = done
                        )
                    }
                }
            }
        }

        override fun onStepCompleted() {
            tick.intValue++
            ViewModelProvider(activity)[HomeViewModel::class.java].setShouldPageForward(true)
        }
    }
}
