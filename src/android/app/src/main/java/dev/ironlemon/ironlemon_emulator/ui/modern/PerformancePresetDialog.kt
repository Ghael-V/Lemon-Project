// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.ui.modern

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.utils.AdaptivePerformance
import dev.ironlemon.ironlemon_emulator.utils.PerformancePresets.Preset

/**
 * Picks a performance preset. [current] is marked (null: custom values, or with [globalOption] the
 * global ones). With [globalOption] the first row gives the global values back (for one game).
 * Without it (the global dialog) the adaptive performance switch is shown under the presets.
 */
fun showPerformancePresetDialog(
    context: Context,
    title: String,
    current: Preset?,
    globalOption: Boolean,
    onPick: (Preset?) -> Unit
) {
    showIronLemonDialog(context) { dismiss ->
        val presets = Preset.entries
        val options = buildList {
            if (globalOption) add(stringResource(R.string.preset_use_global))
            presets.forEach {
                add("${stringResource(it.titleRes)} · ${stringResource(it.descriptionRes)}")
            }
        }
        val offset = if (globalOption) 1 else 0
        val selected = when {
            current != null -> presets.indexOf(current) + offset
            globalOption -> 0
            else -> -1
        }
        IronLemonDialogCard(
            title = title,
            buttons = {
                IronLemonButton(
                    text = stringResource(android.R.string.cancel),
                    height = 44.dp,
                    onClick = dismiss
                )
            }
        ) {
            if (current == null && !globalOption) {
                IronLemonDialogText(stringResource(R.string.preset_custom_values))
                Spacer(Modifier.height(12.dp))
            }
            IronLemonChoiceList(options, selected) { index ->
                onPick(if (index < offset) null else presets[index - offset])
                dismiss()
            }
            if (!globalOption) {
                var adaptive by remember { mutableStateOf(AdaptivePerformance.isEnabled(context)) }
                Spacer(Modifier.height(12.dp))
                IronLemonSwitchRow(stringResource(R.string.adaptive_performance), adaptive) {
                    adaptive = it
                    AdaptivePerformance.setEnabled(context, it)
                }
                Spacer(Modifier.height(6.dp))
                IronLemonDialogText(stringResource(R.string.adaptive_performance_description))
            }
        }
    }
}
