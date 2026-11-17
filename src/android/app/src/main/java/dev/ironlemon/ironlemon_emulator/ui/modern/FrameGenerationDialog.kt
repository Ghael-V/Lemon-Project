// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.ui.modern

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.settings.model.BooleanSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.IntSetting
import dev.ironlemon.ironlemon_emulator.utils.NativeConfig

/** The frame rates offered as Auto's target. */
private val FRAME_GENERATION_TARGETS = listOf(30, 45, 60, 90, 120)

/** What the frame generation card shows: off, or how frames are generated. */
fun frameGenerationSummary(context: Context): String {
    if (!BooleanSetting.FRAME_GENERATION.getBoolean()) {
        return context.getString(R.string.frame_generation_off)
    }
    val mode = IntSetting.FRAME_GENERATION_MODE.getInt()
    return if (mode >= 2) {
        "x$mode"
    } else {
        context.getString(
            R.string.frame_generation_auto_summary,
            IntSetting.FRAME_GENERATION_TARGET.getInt()
        )
    }
}

/**
 * Frame generation for every game in one place: on or off, how many frames (Auto up to a target
 * frame rate, or a fixed multiple) and how finely motion is searched. Each change is saved at once
 * to the global settings; [onChanged] refreshes whatever shows them.
 */
fun showFrameGenerationDialog(context: Context, onChanged: () -> Unit) {
    showIronLemonDialog(context) { dismiss ->
        var enabled by remember { mutableStateOf(BooleanSetting.FRAME_GENERATION.getBoolean()) }
        var mode by remember { mutableIntStateOf(IntSetting.FRAME_GENERATION_MODE.getInt()) }
        var target by remember { mutableIntStateOf(IntSetting.FRAME_GENERATION_TARGET.getInt()) }
        var quality by remember { mutableIntStateOf(IntSetting.FRAME_GENERATION_QUALITY.getInt()) }
        val save = {
            NativeConfig.saveGlobalConfig()
            onChanged()
        }

        IronLemonDialogCard(
            title = stringResource(R.string.frame_generation),
            buttons = {
                IronLemonButton(
                    text = stringResource(R.string.close),
                    height = 44.dp,
                    onClick = dismiss
                )
            }
        ) {
            IronLemonSwitchRow(stringResource(R.string.frame_generation_switch), enabled) {
                enabled = it
                BooleanSetting.FRAME_GENERATION.setBoolean(it)
                save()
            }
            if (enabled) {
                val modeNames = stringArrayResource(R.array.frameGenerationModeNames).toList()
                val modeValues = listOf(0, 2, 3, 4)
                Spacer(Modifier.height(14.dp))
                SectionLabel(stringResource(R.string.frame_generation_mode))
                ChipRow(modeNames, modeValues.indexOf(mode)) { index ->
                    mode = modeValues[index]
                    IntSetting.FRAME_GENERATION_MODE.setInt(mode)
                    save()
                }
                if (mode < 2) {
                    Spacer(Modifier.height(12.dp))
                    SectionLabel(stringResource(R.string.frame_generation_target))
                    ChipRow(
                        FRAME_GENERATION_TARGETS.map { "$it" },
                        FRAME_GENERATION_TARGETS.indexOf(target)
                    ) { index ->
                        target = FRAME_GENERATION_TARGETS[index]
                        IntSetting.FRAME_GENERATION_TARGET.setInt(target)
                        save()
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionLabel(stringResource(R.string.frame_generation_quality))
                ChipRow(
                    stringArrayResource(R.array.frameGenerationQualityNames).toList(),
                    quality
                ) { index ->
                    quality = index
                    IntSetting.FRAME_GENERATION_QUALITY.setInt(index)
                    save()
                }
            }
            if (!enabled) {
                // Once on, the choices take the room (the dialog must fit a landscape screen).
                Spacer(Modifier.height(12.dp))
                IronLemonDialogText(stringResource(R.string.frame_generation_description))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text,
        style = IronLemonType.Body.copy(fontSize = 14.sp, color = IronLemonColors.TextMuted),
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .lemonInteractive(shape = shape, cornerRadius = 12.dp, selected = isSelected) {
                        onSelect(index)
                    }
                    .clip(shape)
                    .background(if (isSelected) IronLemonColors.Lemon else IronLemonColors.SurfaceRaised)
                    .padding(horizontal = 2.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    option,
                    style = IronLemonType.Button.copy(
                        // Long labels ("Performance", "Rendimiento") in a third of the row.
                        fontSize = if (option.length > 9) 11.sp else 12.sp,
                        color = if (isSelected) IronLemonColors.OnLemon else IronLemonColors.Text,
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
