// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dev.ironlemon.ironlemon_emulator.features.settings.model.BooleanSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.ShortSetting
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.utils.PerformancePresets.Preset

/**
 * Adaptive performance: when a game overheats the device or keeps running well below full speed,
 * that game gets the Battery preset. Graphics settings such as the resolution only take effect at
 * boot, and the config must not change under a running game, so the preset is written when the
 * game is next started. On by default; the switch is in the performance preset dialog.
 */
object AdaptivePerformance {
    private const val PREF_ENABLED = "adaptive_performance_enabled"
    private const val PREF_PENDING = "adaptive_performance_pending"

    enum class Reason { THERMAL, SPEED }

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_ENABLED, enabled)
        }

    /** Remembers that [game] should get the Battery preset at its next start. */
    fun markPending(context: Context, game: Game) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val pending = prefs.getStringSet(PREF_PENDING, emptySet())!!.toMutableSet()
        pending.add(game.path)
        prefs.edit { putStringSet(PREF_PENDING, pending) }
    }

    /**
     * Gives [game] the Battery preset if a past session asked for it. Call before its config is
     * loaded for boot. Does nothing when it already gets Battery's values, its own or the global.
     */
    fun applyPending(context: Context, game: Game) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val pending = prefs.getStringSet(PREF_PENDING, emptySet())!!
        if (game.path !in pending) {
            return
        }
        prefs.edit { putStringSet(PREF_PENDING, pending - game.path) }
        if (!isEnabled(context)) {
            return
        }
        val own = PerformancePresets.currentFor(game)
        val effective = own ?: PerformancePresets.current()
        if (effective != Preset.BATTERY) {
            PerformancePresets.applyFor(game, Preset.BATTERY)
        }
    }

    /**
     * Watches one session: the device's thermal status, and the emulation speed sampled once a
     * second. [isActive] says whether the game is actually running (booted, not paused, not
     * stopping); [readSpeed] gives the emulation speed (1.0 = full speed). Fires [onTrigger] once.
     */
    class Monitor(
        private val context: Context,
        private val isActive: () -> Boolean,
        private val readSpeed: () -> Double,
        private val onTrigger: (Reason) -> Unit
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        private var slowSamples = 0
        private var fired = false
        private var running = false

        private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            if (status >= PowerManager.THERMAL_STATUS_SEVERE && isActive()) {
                fire(Reason.THERMAL)
            }
        }

        private val speedSampler = object : Runnable {
            override fun run() {
                if (!running || fired) {
                    return
                }
                if (isActive()) {
                    if (readSpeed() < speedThreshold()) {
                        slowSamples++
                        if (slowSamples >= SLOW_SAMPLES_NEEDED) {
                            fire(Reason.SPEED)
                            return
                        }
                    } else {
                        slowSamples = 0
                    }
                } else {
                    // A pause or a loading screen breaks the streak.
                    slowSamples = 0
                }
                handler.postDelayed(this, SAMPLE_INTERVAL_MS)
            }
        }

        fun start() {
            if (running || fired || !isEnabled(context)) {
                return
            }
            running = true
            slowSamples = 0
            powerManager.addThermalStatusListener(context.mainExecutor, thermalListener)
            handler.postDelayed(speedSampler, SAMPLE_INTERVAL_MS)
        }

        fun stop() {
            if (!running) {
                return
            }
            running = false
            powerManager.removeThermalStatusListener(thermalListener)
            handler.removeCallbacks(speedSampler)
        }

        // A game held to a lower speed limit on purpose is not "slow".
        private fun speedThreshold(): Double {
            val limit = if (BooleanSetting.RENDERER_USE_SPEED_LIMIT.getBoolean()) {
                ShortSetting.RENDERER_SPEED_LIMIT.getShort() / 100.0
            } else {
                1.0
            }
            return SLOW_SPEED_FRACTION * limit.coerceAtMost(1.0)
        }

        private fun fire(reason: Reason) {
            if (fired || !running) {
                return
            }
            fired = true
            stop()
            onTrigger(reason)
        }
    }

    private const val SAMPLE_INTERVAL_MS = 1000L
    private const val SLOW_SPEED_FRACTION = 0.7
    private const val SLOW_SAMPLES_NEEDED = 8 // about 8 s in a row, not one rough moment
}
