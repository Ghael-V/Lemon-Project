// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings.model.view

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

class RunnableSetting(
    @StringRes titleId: Int = 0,
    titleString: String = "",
    @StringRes descriptionId: Int = 0,
    descriptionString: String = "",
    val isRunnable: Boolean,
    @DrawableRes val iconId: Int = 0,
    // Shown greyed out when false (a row that is not runnable is otherwise shown as usual).
    val isEnabled: Boolean = true,
    // A switch shown at the row's end, for a setting that belongs to this one.
    val trailingSwitch: SwitchSetting? = null,
    val runnable: () -> Unit
) : SettingsItem(emptySetting, titleId, titleString, descriptionId, descriptionString) {
    override val type = TYPE_RUNNABLE

    override val isEditable: Boolean
        get() = isEnabled && super.isEditable
}
