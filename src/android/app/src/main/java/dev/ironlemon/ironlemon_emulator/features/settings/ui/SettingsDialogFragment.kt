// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings.ui

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.input.NativeInput
import dev.ironlemon.ironlemon_emulator.features.input.model.AnalogDirection
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.AnalogInputSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.ButtonInputSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.IntSingleChoiceSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.SettingsItem
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.SingleChoiceSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.SliderSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.StringInputSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.StringSingleChoiceSetting
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonChoiceList
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonColors
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonSlider
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonType
import dev.ironlemon.ironlemon_emulator.utils.ParamPackage

/**
 * Changes one setting: pick one of its choices, move its slider, type its text, or confirm putting
 * it back to its default ([TYPE_RESET_SETTING]).
 */
class SettingsDialogFragment : IronLemonComposeDialogFragment() {
    private var type = 0
    private var position = 0

    private val settingsViewModel: SettingsViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = requireArguments().getInt(TYPE)
        position = requireArguments().getInt(POSITION)

        if (settingsViewModel.clickedItem == null) dismiss()
    }

    @Composable
    override fun DialogContent() {
        val item = settingsViewModel.clickedItem ?: return
        when (type) {
            TYPE_RESET_SETTING -> ResetContent()
            SettingsItem.TYPE_SINGLE_CHOICE -> {
                val setting = item as SingleChoiceSetting
                ChoiceContent(
                    setting.title,
                    stringArrayResource(setting.choicesId).toList(),
                    getSelectionForSingleChoiceValue(setting)
                ) { which -> setting.setSelectedValue(getValueForSingleChoiceSelection(setting, which)) }
            }

            SettingsItem.TYPE_STRING_SINGLE_CHOICE -> {
                val setting = item as StringSingleChoiceSetting
                ChoiceContent(setting.title, setting.choices.toList(), setting.selectedValueIndex) {
                    setting.setSelectedValue(setting.getValueAt(it))
                }
            }

            SettingsItem.TYPE_INT_SINGLE_CHOICE -> {
                val setting = item as IntSingleChoiceSetting
                ChoiceContent(setting.title, setting.choices.toList(), setting.selectedValueIndex) {
                    setting.setSelectedValue(setting.getValueAt(it))
                }
            }

            SettingsItem.TYPE_SLIDER -> SliderContent(item as SliderSetting)
            SettingsItem.TYPE_STRING_INPUT -> StringContent(item as StringInputSetting)
        }
    }

    /** A choice applies and closes at once, as the classic single-choice dialog did. */
    @Composable
    private fun ChoiceContent(
        title: String,
        choices: List<String>,
        selected: Int,
        apply: (Int) -> Unit
    ) {
        IronLemonDialogCard(title = title) {
            androidx.compose.foundation.layout.Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())
            ) {
                IronLemonChoiceList(choices, selected) {
                    apply(it)
                    closeDialog()
                }
            }
        }
    }

    @Composable
    private fun SliderContent(setting: SliderSetting) {
        val progress by settingsViewModel.sliderProgress.collectAsState()
        val text by settingsViewModel.sliderTextValue.collectAsState()
        if (text.isEmpty()) {
            settingsViewModel.setSliderTextValue(setting.getSelectedValue().toFloat(), setting.units)
        }
        IronLemonDialogCard(
            title = setting.title,
            buttons = { OkCancel { setting.setSelectedValue(settingsViewModel.sliderProgress.value) } }
        ) {
            BasicText(text, style = IronLemonType.Heading.copy(color = IronLemonColors.Lemon))
            Spacer(Modifier.height(12.dp))
            IronLemonSlider(
                value = progress - setting.min,
                steps = setting.max - setting.min,
                onChange = {
                    settingsViewModel.setSliderTextValue((it + setting.min).toFloat(), setting.units)
                }
            )
        }
    }

    @Composable
    private fun StringContent(setting: StringInputSetting) {
        var text by remember { mutableStateOf(setting.getSelectedValue()) }
        val shape = RoundedCornerShape(16.dp)
        IronLemonDialogCard(
            title = setting.title,
            buttons = { OkCancel { setting.setSelectedValue(text) } }
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = IronLemonType.Button,
                cursorBrush = SolidColor(IronLemonColors.Lemon),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(IronLemonColors.Surface)
                    .border(1.dp, IronLemonColors.Outline, shape)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            )
        }
    }

    @Composable
    private fun ResetContent() {
        IronLemonDialogCard(
            title = null,
            buttons = { OkCancel { resetSetting() } }
        ) {
            IronLemonDialogText(stringResource(R.string.reset_setting_confirmation))
        }
    }

    @Composable
    private fun OkCancel(onOk: () -> Unit) {
        IronLemonButton(
            text = stringResource(android.R.string.cancel),
            height = 44.dp,
            onClick = ::closeDialog
        )
        IronLemonButton(
            text = stringResource(android.R.string.ok),
            primary = true,
            height = 44.dp,
            onClick = {
                onOk()
                closeDialog()
            }
        )
    }

    private fun resetSetting() {
        when (val item = settingsViewModel.clickedItem) {
            is AnalogInputSetting -> {
                val stickParam = NativeInput.getStickParam(
                    item.playerIndex,
                    item.nativeAnalog
                )
                if (stickParam.get("engine", "") == "analog_from_button") {
                    when (item.analogDirection) {
                        AnalogDirection.Up -> stickParam.erase("up")
                        AnalogDirection.Down -> stickParam.erase("down")
                        AnalogDirection.Left -> stickParam.erase("left")
                        AnalogDirection.Right -> stickParam.erase("right")
                    }
                    NativeInput.setStickParam(
                        item.playerIndex,
                        item.nativeAnalog,
                        stickParam
                    )
                    settingsViewModel.setAdapterItemChanged(position)
                } else {
                    NativeInput.setStickParam(
                        item.playerIndex,
                        item.nativeAnalog,
                        ParamPackage()
                    )
                    settingsViewModel.setDatasetChanged(true)
                }
            }

            is ButtonInputSetting -> {
                NativeInput.setButtonParam(
                    item.playerIndex,
                    item.nativeButton,
                    ParamPackage()
                )
                settingsViewModel.setAdapterItemChanged(position)
            }

            else -> {
                settingsViewModel.clickedItem!!.setting.reset()
                settingsViewModel.setAdapterItemChanged(position)
            }
        }
    }

    private fun closeDialog() {
        settingsViewModel.setAdapterItemChanged(position)
        settingsViewModel.clickedItem = null
        settingsViewModel.setSliderProgress(-1f)
        dismiss()
    }

    private fun getValueForSingleChoiceSelection(item: SingleChoiceSetting, which: Int): Int {
        val valuesId = item.valuesId
        return if (valuesId > 0) {
            val valuesArray = requireContext().resources.getIntArray(valuesId)
            valuesArray[which]
        } else {
            which
        }
    }

    private fun getSelectionForSingleChoiceValue(item: SingleChoiceSetting): Int {
        val value = item.getSelectedValue()
        val valuesId = item.valuesId
        if (valuesId > 0) {
            val valuesArray = requireContext().resources.getIntArray(valuesId)
            for (index in valuesArray.indices) {
                val current = valuesArray[index]
                if (current == value) {
                    return index
                }
            }
        } else {
            return value
        }
        return -1
    }

    companion object {
        const val TAG = "SettingsDialogFragment"

        const val TYPE_RESET_SETTING = -1

        const val TITLE = "Title"
        const val TYPE = "Type"
        const val POSITION = "Position"

        fun newInstance(
            settingsViewModel: SettingsViewModel,
            clickedItem: SettingsItem,
            type: Int,
            position: Int
        ): SettingsDialogFragment {
            when (type) {
                SettingsItem.TYPE_HEADER,
                SettingsItem.TYPE_SWITCH,
                SettingsItem.TYPE_SUBMENU,
                SettingsItem.TYPE_DATETIME_SETTING,
                SettingsItem.TYPE_RUNNABLE ->
                    throw IllegalArgumentException("[SettingsDialogFragment] Incompatible type!")

                SettingsItem.TYPE_SLIDER -> settingsViewModel.setSliderProgress(
                    (clickedItem as SliderSetting).getSelectedValue().toFloat()
                )
            }
            settingsViewModel.clickedItem = clickedItem

            val args = Bundle()
            args.putInt(TYPE, type)
            args.putInt(POSITION, position)
            val fragment = SettingsDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
