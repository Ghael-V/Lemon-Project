// SPDX-FileCopyrightText: 2024 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings.model.view

import androidx.annotation.StringRes
import dev.ironlemon.ironlemon_emulator.features.input.NativeInput
import dev.ironlemon.ironlemon_emulator.features.input.model.InputType
import dev.ironlemon.ironlemon_emulator.features.input.model.NativeAnalog
import dev.ironlemon.ironlemon_emulator.utils.ParamPackage

class ModifierInputSetting(
    override val playerIndex: Int,
    val nativeAnalog: NativeAnalog,
    @StringRes titleId: Int = 0,
    titleString: String = ""
) : InputSetting(titleId, titleString) {
    override val inputType = InputType.Button

    override fun getSelectedValue(): String {
        val analogParam = NativeInput.getStickParam(playerIndex, nativeAnalog)
        val modifierParam = ParamPackage(analogParam.get("modifier", ""))
        return buttonToText(modifierParam)
    }

    override fun setSelectedValue(param: ParamPackage) {
        val newParam = NativeInput.getStickParam(playerIndex, nativeAnalog)
        newParam.set("modifier", param.serialize())
        NativeInput.setStickParam(playerIndex, nativeAnalog, newParam)
    }
}
