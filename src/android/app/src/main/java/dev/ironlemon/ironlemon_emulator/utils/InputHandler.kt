// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.ironlemon.ironlemon_emulator.features.input.NativeInput
import dev.ironlemon.ironlemon_emulator.features.input.YuzuInputOverlayDevice
import dev.ironlemon.ironlemon_emulator.features.input.YuzuPhysicalDevice

object InputHandler {
    var androidControllers = mapOf<Int, YuzuPhysicalDevice>()
    var registeredControllers = mutableListOf<ParamPackage>()

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> NativeInput.ButtonState.PRESSED
            KeyEvent.ACTION_UP -> NativeInput.ButtonState.RELEASED
            else -> return false
        }

        // Key events injected by the system or the on-screen keyboard have no input device.
        val device = event.device ?: return false
        var controllerData = androidControllers[device.controllerNumber]
        if (controllerData == null) {
            updateControllerData()
            controllerData = androidControllers[device.controllerNumber] ?: return false
        }

        NativeInput.onGamePadButtonEvent(
            controllerData.getGUID(),
            controllerData.getPort(),
            event.keyCode,
            action
        )
        return true
    }

    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        val controllerData =
            androidControllers[device.controllerNumber] ?: return false
        device.motionRanges.forEach {
            NativeInput.onGamePadAxisEvent(
                controllerData.getGUID(),
                controllerData.getPort(),
                it.axis,
                event.getAxisValue(it.axis)
            )
        }
        return true
    }

    fun getDevices(): Map<Int, YuzuPhysicalDevice> {
        val gameControllerDeviceIds = mutableMapOf<Int, YuzuPhysicalDevice>()
        val deviceIds = InputDevice.getDeviceIds()
        var port = 0
        val inputSettings = NativeConfig.getInputSettings(true)
        deviceIds.forEach { deviceId ->
            InputDevice.getDevice(deviceId)?.apply {
                // Verify that the device has gamepad buttons, control sticks, or both.
                if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
                ) {
                    if (!gameControllerDeviceIds.contains(controllerNumber)) {
                        gameControllerDeviceIds[controllerNumber] = YuzuPhysicalDevice(
                            this,
                            port,
                            inputSettings[port].useSystemVibrator
                        )
                    }
                    port++
                }
            }
        }
        return gameControllerDeviceIds
    }

    fun updateControllerData() {
        androidControllers = getDevices()
        androidControllers.forEach {
            NativeInput.registerController(it.value)
        }

        // Register the input overlay on a dedicated port for all player 1 vibrations
        NativeInput.registerController(YuzuInputOverlayDevice(androidControllers.isEmpty(), 100))
        registeredControllers.clear()
        NativeInput.getInputDevices().forEach {
            registeredControllers.add(ParamPackage(it))
        }
        registeredControllers.sortBy { it.get("port", 0) }
    }

    fun InputDevice.getGUID(): String = String.format("%016x%016x", productId, vendorId)
}
