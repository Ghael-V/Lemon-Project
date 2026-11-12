// SPDX-FileCopyrightText: 2024 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings.model.view

import androidx.annotation.StringRes
import dev.ironlemon.ironlemon_emulator.utils.ParamPackage
import dev.ironlemon.ironlemon_emulator.features.input.NativeInput
import dev.ironlemon.ironlemon_emulator.features.input.model.InputType
import dev.ironlemon.ironlemon_emulator.features.input.model.NativeButton

class ButtonInputSetting(
    override val playerIndex: Int,
    val nativeButton: NativeButton,
    @StringRes titleId: Int = 0,
    titleString: String = ""
) : InputSetting(titleId, titleString) {
    override val type = TYPE_INPUT
    override val inputType = InputType.Button

    override fun getSelectedValue(): String {
        val params = NativeInput.getButtonParam(playerIndex, nativeButton)
        val button = buttonToText(params)
        return getDisplayString(params, button)
    }

    override fun setSelectedValue(param: ParamPackage) =
        NativeInput.setButtonParam(playerIndex, nativeButton, param)
}
