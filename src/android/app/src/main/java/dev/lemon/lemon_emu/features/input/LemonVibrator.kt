// SPDX-FileCopyrightText: 2024 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.lemon.lemon_emu.features.input

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import dev.lemon.lemon_emu.LemonApplication

@Keep
@Suppress("DEPRECATION")
interface LemonVibrator {
    fun supportsVibration(): Boolean

    fun vibrate(intensity: Float)

    companion object {
        fun getControllerVibrator(device: InputDevice): LemonVibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                LemonVibratorManager(device.vibratorManager)
            } else {
                LemonVibratorManagerCompat(device.vibrator)
            }

        fun getSystemVibrator(): LemonVibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = LemonApplication.appContext
                    .getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                LemonVibratorManager(vibratorManager)
            } else {
                val vibrator = LemonApplication.appContext
                    .getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                LemonVibratorManagerCompat(vibrator)
            }

        fun getVibrationEffect(intensity: Float): VibrationEffect? {
            if (intensity > 0f) {
                return VibrationEffect.createOneShot(
                    50,
                    (255.0 * intensity).toInt().coerceIn(1, 255)
                )
            }
            return null
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
class LemonVibratorManager(private val vibratorManager: VibratorManager) : LemonVibrator {
    override fun supportsVibration(): Boolean {
        return vibratorManager.vibratorIds.isNotEmpty()
    }

    override fun vibrate(intensity: Float) {
        val vibration = LemonVibrator.getVibrationEffect(intensity) ?: return
        vibratorManager.vibrate(CombinedVibration.createParallel(vibration))
    }
}

class LemonVibratorManagerCompat(private val vibrator: Vibrator) : LemonVibrator {
    override fun supportsVibration(): Boolean {
        return vibrator.hasVibrator()
    }

    override fun vibrate(intensity: Float) {
        val vibration = LemonVibrator.getVibrationEffect(intensity) ?: return
        vibrator.vibrate(vibration)
    }
}
