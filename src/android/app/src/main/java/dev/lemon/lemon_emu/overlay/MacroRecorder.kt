// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.overlay

import android.os.SystemClock
import dev.lemon.lemon_emu.features.input.model.NativeAnalog
import dev.lemon.lemon_emu.features.input.model.NativeButton

/**
 * Shared recording state between [InputOverlay] (the producer - every on-screen button/stick
 * event passes through here while recording) and [MacroOverlay] (the UI that starts/stops
 * recording and drives playback). The two are sibling views with no direct reference to each
 * other, same relationship as [InputOverlay] and [CheatOverlay] - this is the shared point
 * between them, same role [dev.lemon.lemon_emu.features.input.NativeInput] plays for raw input.
 */
object MacroRecorder {
    sealed class Event {
        abstract val delayMs: Long

        data class Button(
            override val delayMs: Long,
            val port: Int,
            val button: NativeButton,
            val action: Int
        ) : Event()

        data class Joystick(
            override val delayMs: Long,
            val port: Int,
            val stick: NativeAnalog,
            val x: Float,
            val y: Float
        ) : Event()
    }

    var isRecording = false
        private set

    /** The most recently completed recording - kept around after [stop] so it can be played. */
    var lastRecordedMacro: List<Event> = emptyList()
        private set

    /** Live count of events captured so far while [isRecording] - for the "Recording... N
     *  events" status text. */
    val recordedEventCount: Int
        get() = events.size

    private val events = mutableListOf<Event>()
    private var lastEventTime = 0L

    fun start() {
        events.clear()
        lastEventTime = SystemClock.elapsedRealtime()
        isRecording = true
    }

    fun stop(): List<Event> {
        isRecording = false
        lastRecordedMacro = events.toList()
        return lastRecordedMacro
    }

    fun recordButtonEvent(port: Int, button: NativeButton, action: Int) {
        if (!isRecording) return
        events.add(Event.Button(nextDelay(), port, button, action))
    }

    fun recordJoystickEvent(port: Int, stick: NativeAnalog, x: Float, y: Float) {
        if (!isRecording) return
        events.add(Event.Joystick(nextDelay(), port, stick, x, y))
    }

    private fun nextDelay(): Long {
        val now = SystemClock.elapsedRealtime()
        val delay = now - lastEventTime
        lastEventTime = now
        return delay
    }
}
