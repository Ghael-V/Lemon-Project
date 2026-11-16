// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.activities

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.settings.model.BooleanSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.IntSetting
import dev.ironlemon.ironlemon_emulator.overlay.InputOverlay
import dev.ironlemon.ironlemon_emulator.overlay.model.OverlayLayout
import dev.ironlemon.ironlemon_emulator.overlay.model.OverlayPreset
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonChoiceList
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.showIronLemonDialog
import dev.ironlemon.ironlemon_emulator.utils.NativeConfig

/**
 * Edits the on-screen controller without a game: the same editor as in game (move a control,
 * tap one for its size and button mode) over an empty screen, for the layout every game uses.
 */
class OverlayEditorActivity : AppCompatActivity() {
    private lateinit var controls: InputOverlay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        controls = InputOverlay(this, null).apply {
            layout = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                OverlayLayout.Portrait
            } else {
                OverlayLayout.Landscape
            }
            // Shown here even when a controller would hide it in game.
            alwaysShown = true
            setIsInEditMode(true)
        }
        val toolbar = ComposeView(this).apply {
            setContent { Toolbar() }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                controls,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                toolbar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                )
            )
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = done()
            }
        )
    }

    @androidx.compose.runtime.Composable
    private fun Toolbar() {
        var snap by remember { mutableStateOf(BooleanSetting.OVERLAY_SNAP_TO_GRID.getBoolean()) }
        Row(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IronLemonButton(
                text = stringResource(R.string.overlay_presets),
                height = 40.dp,
                onClick = ::showPresets
            )
            IronLemonButton(
                text = stringResource(
                    if (snap) R.string.overlay_grid_on else R.string.overlay_grid_off
                ),
                height = 40.dp,
                onClick = {
                    snap = !snap
                    BooleanSetting.OVERLAY_SNAP_TO_GRID.setBoolean(snap)
                    controls.invalidate()
                }
            )
            IronLemonButton(
                text = stringResource(R.string.emulation_touch_overlay_reset),
                height = 40.dp,
                onClick = {
                    IntSetting.OVERLAY_SCALE.reset()
                    IntSetting.OVERLAY_OPACITY.reset()
                    controls.resetLayoutVisibilityAndPlacement()
                }
            )
            IronLemonButton(
                text = stringResource(R.string.done),
                primary = true,
                height = 40.dp,
                onClick = ::done
            )
        }
    }

    private fun showPresets() {
        val presets = OverlayPreset.entries
        showIronLemonDialog(this) { dismiss ->
            IronLemonDialogCard(
                title = stringResource(R.string.overlay_presets),
                buttons = {
                    IronLemonButton(
                        text = stringResource(android.R.string.cancel),
                        height = 44.dp,
                        onClick = dismiss
                    )
                }
            ) {
                IronLemonChoiceList(presets.map { stringResource(it.titleRes) }, -1) { index ->
                    dismiss()
                    controls.applyPreset(presets[index])
                }
            }
        }
    }

    private fun done() {
        NativeConfig.saveGlobalConfig()
        finish()
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, OverlayEditorActivity::class.java)
            // Settings may hand over the application context.
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
