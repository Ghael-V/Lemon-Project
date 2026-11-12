// SPDX-FileCopyrightText: 2024 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings.ui

import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.activityViewModels
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.input.NativeInput
import dev.ironlemon.ironlemon_emulator.features.input.model.NativeAnalog
import dev.ironlemon.ironlemon_emulator.features.input.model.NativeButton
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.AnalogInputSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.ButtonInputSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.InputSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.ModifierInputSetting
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText
import dev.ironlemon.ironlemon_emulator.utils.InputHandler
import dev.ironlemon.ironlemon_emulator.utils.ParamPackage

class InputDialogFragment : IronLemonComposeDialogFragment() {
    private var inputAccepted = false

    private var position: Int = 0

    private lateinit var inputSetting: InputSetting

    private val settingsViewModel: SettingsViewModel by activityViewModels()

    // The animated hints: a stick moving one or both ways, then a button press, in turn.
    private var stickImage: ImageView? = null
    private var buttonImage: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (settingsViewModel.clickedItem == null) {
            dismiss()
            return
        }
        inputSetting = settingsViewModel.clickedItem as InputSetting

        position = requireArguments().getInt(POSITION)

        InputHandler.updateControllerData()
    }

    private fun titleAndMessage(): Pair<String, Int> = when (val setting = inputSetting) {
        is AnalogInputSetting -> {
            val stick = when (setting.nativeAnalog) {
                NativeAnalog.LStick -> getString(R.string.left_stick)
                NativeAnalog.RStick -> getString(R.string.right_stick)
            }
            getString(R.string.map_control, stick) to R.string.stick_map_description
        }

        is ModifierInputSetting ->
            getString(R.string.map_control, setting.title) to R.string.button_map_description

        is ButtonInputSetting -> {
            val dpad = setting.nativeButton == NativeButton.DUp ||
                setting.nativeButton == NativeButton.DDown ||
                setting.nativeButton == NativeButton.DLeft ||
                setting.nativeButton == NativeButton.DRight
            getString(if (dpad) R.string.map_dpad_direction else R.string.map_control, setting.title) to
                R.string.button_map_description
        }

        else -> "" to R.string.button_map_description
    }

    @Composable
    override fun DialogContent() {
        if (!this::inputSetting.isInitialized) return
        val (title, message) = titleAndMessage()
        IronLemonDialogCard(
            title = title,
            buttons = {
                IronLemonButton(
                    text = stringResource(android.R.string.cancel),
                    height = 44.dp,
                    onClick = {
                        NativeInput.stopMapping()
                        dismiss()
                    }
                )
            }
        ) {
            IronLemonDialogText(stringResource(message))
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AndroidView(
                    factory = { ImageView(it).also { view -> stickImage = view } },
                    modifier = Modifier.size(96.dp)
                )
                Spacer(Modifier.width(32.dp))
                AndroidView(
                    factory = { ImageView(it).also { view -> buttonImage = view } },
                    modifier = Modifier.size(96.dp)
                )
            }
        }
    }

    private fun playButtonMapAnimation(twoDirections: Boolean) {
        val stick = stickImage ?: return
        val button = buttonImage ?: return
        stick.setBackgroundResource(
            if (twoDirections) R.drawable.stick_two_direction_anim else R.drawable.stick_one_direction_anim
        )
        button.setBackgroundResource(R.drawable.button_anim)
        val stickAnimation = stick.background as AnimatedVectorDrawable
        val buttonAnimation = button.background as AnimatedVectorDrawable
        stickAnimation.registerAnimationCallback(object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                buttonAnimation.start()
            }
        })
        buttonAnimation.registerAnimationCallback(object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                stickAnimation.start()
            }
        })
        stickAnimation.start()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!this::inputSetting.isInitialized) return
        // Every key and axis of the controller goes to the mapping, not to the dialog's buttons.
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnFocusChangeListener { v, hasFocus -> if (!hasFocus) v.requestFocus() }
        dialog?.setOnKeyListener { _, _, keyEvent -> onKeyEvent(keyEvent) }
        view.setOnGenericMotionListener { _, motionEvent -> onMotionEvent(motionEvent) }
        // The hint images exist once the content is laid out.
        view.post { playButtonMapAnimation(inputSetting is AnalogInputSetting) }
        NativeInput.beginMapping(inputSetting.inputType.int)
    }

    private fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }

        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> NativeInput.ButtonState.PRESSED
            KeyEvent.ACTION_UP -> NativeInput.ButtonState.RELEASED
            else -> return false
        }
        val controllerData =
            InputHandler.androidControllers[event.device.controllerNumber] ?: return false
        NativeInput.onGamePadButtonEvent(
            controllerData.getGUID(),
            controllerData.getPort(),
            event.keyCode,
            action
        )
        onInputReceived(event.device)
        return true
    }

    private fun onMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }

        // Temp workaround for DPads that give both axis and button input. The input system can't
        // take in a specific axis direction for a binding so you lose half of the directions for a DPad.

        val controllerData =
            InputHandler.androidControllers[event.device.controllerNumber] ?: return false
        event.device.motionRanges.forEach {
            NativeInput.onGamePadAxisEvent(
                controllerData.getGUID(),
                controllerData.getPort(),
                it.axis,
                event.getAxisValue(it.axis)
            )
            onInputReceived(event.device)
        }
        return true
    }

    private fun onInputReceived(device: InputDevice) {
        val params = ParamPackage(NativeInput.getNextInput())
        if (params.has("engine") && isInputAcceptable(params) && !inputAccepted) {
            inputAccepted = true
            setResult(params, device)
        }
    }

    private fun setResult(params: ParamPackage, device: InputDevice) {
        NativeInput.stopMapping()
        params.set("display", "${device.name} ${params.get("port", 0)}")
        when (val item = settingsViewModel.clickedItem as InputSetting) {
            is ModifierInputSetting,
            is ButtonInputSetting -> {
                // Invert DPad up and left bindings by default
                val tempSetting = inputSetting as? ButtonInputSetting
                if (tempSetting != null) {
                    if (tempSetting.nativeButton == NativeButton.DUp ||
                        tempSetting.nativeButton == NativeButton.DLeft &&
                        params.has("axis")
                    ) {
                        params.set("invert", "-")
                    }
                }

                item.setSelectedValue(params)
                settingsViewModel.setAdapterItemChanged(position)
            }

            is AnalogInputSetting -> {
                var analogParam = NativeInput.getStickParam(item.playerIndex, item.nativeAnalog)
                analogParam = adjustAnalogParam(params, analogParam, item.analogDirection.param)

                // Invert Y-Axis by default
                analogParam.set("invert_y", "-")

                item.setSelectedValue(analogParam)
                settingsViewModel.setReloadListAndNotifyDataset(true)
            }
        }
        dismiss()
    }

    private fun adjustAnalogParam(
        inputParam: ParamPackage,
        analogParam: ParamPackage,
        buttonName: String
    ): ParamPackage {
        // The poller returned a complete axis, so set all the buttons
        if (inputParam.has("axis_x") && inputParam.has("axis_y")) {
            return inputParam
        }

        // Check if the current configuration has either no engine or an axis binding.
        // Clears out the old binding and adds one with analog_from_button.
        if (!analogParam.has("engine") || analogParam.has("axis_x") || analogParam.has("axis_y")) {
            analogParam.clear()
            analogParam.set("engine", "analog_from_button")
        }
        analogParam.set(buttonName, inputParam.serialize())
        return analogParam
    }

    private fun isInputAcceptable(params: ParamPackage): Boolean {
        if (InputHandler.registeredControllers.size == 1) {
            return true
        }

        if (params.has("motion")) {
            return true
        }

        val currentDevice = settingsViewModel.getCurrentDeviceParams(params)
        if (currentDevice.get("engine", "any") == "any") {
            return true
        }

        val guidMatch = params.get("guid", "") == currentDevice.get("guid", "") ||
            params.get("guid", "") == currentDevice.get("guid2", "")
        return params.get("engine", "") == currentDevice.get("engine", "") &&
            guidMatch &&
            params.get("port", 0) == currentDevice.get("port", 0)
    }

    companion object {
        const val TAG = "InputDialogFragment"

        const val POSITION = "Position"

        fun newInstance(
            inputMappingViewModel: SettingsViewModel,
            setting: InputSetting,
            position: Int
        ): InputDialogFragment {
            inputMappingViewModel.clickedItem = setting
            val args = Bundle()
            args.putInt(POSITION, position)
            val fragment = InputDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
