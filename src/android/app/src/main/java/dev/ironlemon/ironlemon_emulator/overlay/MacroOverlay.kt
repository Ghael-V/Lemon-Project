// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.overlay

import dev.ironlemon.ironlemon_emulator.ui.modern.LocalLemonPulseActive
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.overlay.OnScreenControls
import dev.ironlemon.ironlemon_emulator.features.input.NativeInput
import dev.ironlemon.ironlemon_emulator.features.input.model.NativeButton
import dev.ironlemon.ironlemon_emulator.features.input.model.NativeAnalog
import dev.ironlemon.ironlemon_emulator.features.settings.model.BooleanSetting
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButtonRow
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonColors
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonIconButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonSwitchRow
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonType

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
    // What the panel shows, drawn by [MacroPanel].
    private var statusText by mutableStateOf("")
    private var recordLabel by mutableIntStateOf(R.string.lemon_macro_record)
    private var recordEnabled by mutableStateOf(true)
    private var playLabel by mutableIntStateOf(R.string.lemon_macro_play)
    private var playEnabled by mutableStateOf(false)
    private var overlayWarning by mutableStateOf(false)
    private var repeat by mutableStateOf(false)

    private val panel: ComposeView

    // Guards onVisibilityChanged(), which can fire from within the View constructor chain
    // before init{} has run.
    private var isSetUp = false

    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private var playbackJob: Job? = null
    private var recordingStatusPollJob: Job? = null

    /** Set by EmulationFragment (the only thing that can actually re-show the on-screen
     *  controller, via its own toggleOverlay() which also keeps its auto-hide/menu-sync
     *  bookkeeping consistent) - invoked when the user taps "Show on-screen controller". */
    var onShowOverlayRequested: (() -> Unit)? = null

    // The panel is composed for the whole game and hidden most of it: its selected items only
    // pulse while it is on screen (an endless animation redraws on every display frame).
    private val panelShown = mutableStateOf(false)

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (panelShown.value != isVisible) {
            panelShown.value = isVisible
        }
    }

    init {
        panel = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalLemonPulseActive provides panelShown.value) {
                    MacroPanel()
                }
            }
        }
        addView(
            panel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )
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

    private fun dragBy(dx: Float, dy: Float) {
        panel.translationX += dx
        panel.translationY += dy
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
            // What the macro is holding right now, so stopping it in the middle (or a recording
            // that ends with a button down) does not leave a button pressed or a stick pushed.
            val held = mutableSetOf<Pair<Int, NativeButton>>()
            val pushed = mutableSetOf<Pair<Int, NativeAnalog>>()
            try {
                do {
                    for (macroEvent in macro) {
                        delay(macroEvent.delayMs)
                        when (macroEvent) {
                            is MacroRecorder.Event.Button -> {
                                NativeInput.onOverlayButtonEvent(
                                    macroEvent.port,
                                    macroEvent.button,
                                    macroEvent.action
                                )
                                val key = macroEvent.port to macroEvent.button
                                if (macroEvent.action == NativeInput.ButtonState.PRESSED) {
                                    held.add(key)
                                } else {
                                    held.remove(key)
                                }
                            }
                            is MacroRecorder.Event.Joystick -> {
                                NativeInput.onOverlayJoystickEvent(
                                    macroEvent.port,
                                    macroEvent.stick,
                                    macroEvent.x,
                                    macroEvent.y
                                )
                                val key = macroEvent.port to macroEvent.stick
                                if (macroEvent.x == 0f && macroEvent.y == 0f) {
                                    pushed.remove(key)
                                } else {
                                    pushed.add(key)
                                }
                            }
                        }
                    }
                } while (repeat)
            } finally {
                held.forEach { (port, button) ->
                    NativeInput.onOverlayButtonEvent(port, button, NativeInput.ButtonState.RELEASED)
                }
                pushed.forEach { (port, stick) ->
                    NativeInput.onOverlayJoystickEvent(port, stick, 0f, 0f)
                }
            }

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
    private fun isOnScreenControllerVisible(): Boolean = OnScreenControls.shouldShow()

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // This can fire from within the View constructor chain, before init{} has run.
        if (visibility == View.VISIBLE && isSetUp) {
            updateStatus()
        }
    }

    private fun updateStatus() {
        val isRecording = MacroRecorder.isRecording
        val isPlaying = playbackJob != null
        val overlayVisible = isOnScreenControllerVisible()

        overlayWarning = !overlayVisible
        recordLabel = if (isRecording) R.string.lemon_macro_stop_recording else R.string.lemon_macro_record
        recordEnabled = !isPlaying && (isRecording || overlayVisible)
        playLabel = if (isPlaying) R.string.lemon_macro_stop_playing else R.string.lemon_macro_play
        playEnabled = !isRecording && (isPlaying || MacroRecorder.lastRecordedMacro.isNotEmpty())

        statusText = when {
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

    @Composable
    private fun MacroPanel() {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .width(380.dp)
                .clip(shape)
                .background(IronLemonColors.Background.copy(alpha = 0.96f))
                .border(1.dp, IronLemonColors.gradient(0.6f), shape)
        ) {
            // The title bar drags the panel around.
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            dragBy(amount.x, amount.y)
                        }
                    }
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    stringResource(R.string.lemon_macro),
                    style = IronLemonType.Heading,
                    modifier = Modifier.weight(1f)
                )
                IronLemonIconButton(
                    iconRes = R.drawable.ic_clear,
                    description = stringResource(R.string.emulation_done),
                    size = 40.dp,
                    onClick = ::hide
                )
            }
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
                if (overlayWarning) {
                    BasicText(
                        stringResource(R.string.lemon_macro_overlay_hidden_warning),
                        style = IronLemonType.Body.copy(color = IronLemonColors.Orange)
                    )
                    Spacer(Modifier.height(8.dp))
                    IronLemonButton(
                        text = stringResource(R.string.lemon_macro_show_overlay),
                        height = 40.dp,
                        onClick = {
                            onShowOverlayRequested?.invoke()
                            updateStatus()
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                }
                BasicText(statusText, style = IronLemonType.Body.copy(color = IronLemonColors.Text))
                Spacer(Modifier.height(12.dp))
                IronLemonButtonRow {
                    IronLemonButton(
                        text = stringResource(recordLabel),
                        iconRes = R.drawable.ic_record,
                        height = 44.dp,
                        modifier = Modifier.alpha(if (recordEnabled) 1f else 0.4f),
                        onClick = { if (recordEnabled) onRecordClicked() }
                    )
                    IronLemonButton(
                        text = stringResource(playLabel),
                        iconRes = R.drawable.ic_play,
                        primary = playEnabled,
                        height = 44.dp,
                        modifier = Modifier.alpha(if (playEnabled) 1f else 0.4f),
                        onClick = { if (playEnabled) onPlayClicked() }
                    )
                }
                Spacer(Modifier.height(12.dp))
                IronLemonSwitchRow(stringResource(R.string.lemon_macro_repeat), repeat) { repeat = it }
            }
        }
    }
}
