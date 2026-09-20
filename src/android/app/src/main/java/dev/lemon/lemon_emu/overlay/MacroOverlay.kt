// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.databinding.OverlayMacroPanelBinding
import dev.lemon.lemon_emu.features.input.NativeInput
import dev.lemon.lemon_emu.features.settings.model.BooleanSetting

/**
 * Input macro recorder/player: records a sequence of on-screen controller events (via
 * [MacroRecorder], fed by [InputOverlay]) and can play it back, looping if requested. Scoped
 * to the on-screen overlay only (not physical controllers) - see the plan in
 * memory/ROADMAP.md history for why.
 *
 * Structurally a near-copy of [CheatOverlay]: same floating draggable card, same hide()-doesn't-
 * destroy-state philosophy (so a recording/playback keeps going while the panel is hidden and
 * only really stops when this view is torn down with the emulation session).
 */
class MacroOverlay(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private val binding: OverlayMacroPanelBinding

    // Guards onVisibilityChanged(), which can fire from within the View constructor chain
    // before init{} (and so `binding`) has run.
    private var isSetUp = false

    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private var playbackJob: Job? = null
    private var recordingStatusPollJob: Job? = null

    /** Set by EmulationFragment (the only thing that can actually re-show the on-screen
     *  controller, via its own toggleOverlay() which also keeps its auto-hide/menu-sync
     *  bookkeeping consistent) - invoked when the user taps "Show on-screen controller". */
    var onShowOverlayRequested: (() -> Unit)? = null

    init {
        binding = OverlayMacroPanelBinding.inflate(LayoutInflater.from(context), this, false)
        addView(
            binding.root,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )

        binding.macroPanelClose.setOnClickListener { hide() }
        binding.macroRecordButton.setOnClickListener { onRecordClicked() }
        binding.macroPlayButton.setOnClickListener { onPlayClicked() }
        binding.macroShowOverlayButton.setOnClickListener {
            onShowOverlayRequested?.invoke()
            updateStatus()
        }

        setupDragHandle()
        isSetUp = true
        updateStatus()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Only if actually recording - MacroRecorder.stop() unconditionally overwrites
        // lastRecordedMacro, which would wipe out an earlier completed recording if this view
        // is torn down while merely idle (e.g. the emulation session ending normally).
        if (MacroRecorder.isRecording) {
            MacroRecorder.stop()
        }
        scope.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + Job())
        }
    }

    private fun hide() {
        visibility = View.GONE
    }

    private fun setupDragHandle() {
        var startRawX = 0f
        var startRawY = 0f
        var startTranslationX = 0f
        var startTranslationY = 0f

        binding.macroPanelTitleBar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startTranslationX = binding.root.translationX
                    startTranslationY = binding.root.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    binding.root.translationX = startTranslationX + (event.rawX - startRawX)
                    binding.root.translationY = startTranslationY + (event.rawY - startRawY)
                    true
                }
                else -> false
            }
        }
    }

    private fun onRecordClicked() {
        if (MacroRecorder.isRecording) {
            MacroRecorder.stop()
            recordingStatusPollJob?.cancel()
            recordingStatusPollJob = null
        } else {
            MacroRecorder.start()
            // Live-refreshes the "Recording... N events" count as touches come in from
            // InputOverlay, which has no reference back to this panel to push updates itself.
            recordingStatusPollJob = scope.launch {
                while (isActive && MacroRecorder.isRecording) {
                    updateStatus()
                    delay(300)
                }
            }
        }
        updateStatus()
    }

    private fun onPlayClicked() {
        if (playbackJob != null) {
            stopPlayback()
            return
        }

        val macro = MacroRecorder.lastRecordedMacro
        if (macro.isEmpty()) {
            return
        }

        playbackJob = scope.launch {
            do {
                for (macroEvent in macro) {
                    delay(macroEvent.delayMs)
                    when (macroEvent) {
                        is MacroRecorder.Event.Button -> NativeInput.onOverlayButtonEvent(
                            macroEvent.port,
                            macroEvent.button,
                            macroEvent.action
                        )
                        is MacroRecorder.Event.Joystick -> NativeInput.onOverlayJoystickEvent(
                            macroEvent.port,
                            macroEvent.stick,
                            macroEvent.x,
                            macroEvent.y
                        )
                    }
                }
            } while (binding.macroRepeatSwitch.isChecked)

            playbackJob = null
            updateStatus()
        }
        updateStatus()
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        updateStatus()
    }

    /** Recording only captures input that actually reaches [InputOverlay] - if the on-screen
     *  controller is hidden, touches never fire onOverlayButtonEvent/onOverlayJoystickEvent at
     *  all, so a recording started now would silently capture nothing. Playback isn't affected
     *  by this - it calls NativeInput directly, bypassing the overlay view entirely. */
    private fun isOnScreenControllerVisible(): Boolean = BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // This can fire from within the View constructor chain, before init{} (and so
        // `binding`) has run.
        if (visibility == View.VISIBLE && isSetUp) {
            updateStatus()
        }
    }

    private fun updateStatus() {
        val isRecording = MacroRecorder.isRecording
        val isPlaying = playbackJob != null
        val overlayVisible = isOnScreenControllerVisible()

        binding.macroOverlayWarning.visibility = if (overlayVisible) View.GONE else View.VISIBLE

        binding.macroRecordButton.text = context.getString(
            if (isRecording) R.string.lemon_macro_stop_recording else R.string.lemon_macro_record
        )
        binding.macroRecordButton.isEnabled = !isPlaying && (isRecording || overlayVisible)
        binding.macroPlayButton.text = context.getString(
            if (isPlaying) R.string.lemon_macro_stop_playing else R.string.lemon_macro_play
        )
        binding.macroPlayButton.isEnabled = !isRecording &&
            (isPlaying || MacroRecorder.lastRecordedMacro.isNotEmpty())

        binding.macroStatusText.text = when {
            isRecording -> context.getString(
                R.string.lemon_macro_status_recording,
                MacroRecorder.recordedEventCount
            )
            isPlaying -> context.getString(R.string.lemon_macro_status_playing)
            MacroRecorder.lastRecordedMacro.isEmpty() -> context.getString(R.string.lemon_macro_no_macro)
            else -> context.getString(
                R.string.lemon_macro_status_ready,
                MacroRecorder.lastRecordedMacro.size
            )
        }
    }
}
