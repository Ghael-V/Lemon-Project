// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.ui.modern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import dev.ironlemon.ironlemon_emulator.R

/** One quick setting: where its value comes from and where a change goes. */
sealed interface QuickSetting {
    val titleRes: Int

    class Toggle(
        override val titleRes: Int,
        val enabled: () -> Boolean = { true },
        val get: () -> Boolean,
        val set: (Boolean) -> Unit
    ) : QuickSetting

    /** A percentage slider from [min] to [max] in [step]s, shown when [visible]. */
    class Percent(
        override val titleRes: Int,
        val min: Int,
        val max: Int,
        val step: Int,
        val visible: () -> Boolean = { true },
        val get: () -> Int,
        val set: (Int) -> Unit
    ) : QuickSetting

    /** One value out of [namesRes], stored as the matching entry of [values]. */
    class Choice(
        override val titleRes: Int,
        val namesRes: Int,
        val values: IntArray,
        val get: () -> Int,
        val set: (Int) -> Unit
    ) : QuickSetting
}

/**
 * The in-game quick settings, on the right of the screen: what is worth changing while playing,
 * applied at once. [tick] makes it read every value again and take focus when the drawer opens.
 * [scope] says whose settings they are (this game's or every game's).
 */
@Composable
fun QuickSettingsPanel(
    tick: Int,
    scope: String,
    settings: List<QuickSetting>,
    onChanged: () -> Unit
) {
    var version by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(tick) {
        delay(200)
        runCatching { first.requestFocus() }
    }
    val changed: () -> Unit = {
        onChanged()
        version += 1
    }
    Column(
        Modifier
            .width(360.dp)
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(IronLemonColors.Background.copy(alpha = 0.97f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BasicText(
            stringResource(R.string.quick_settings),
            style = IronLemonType.Heading.copy(fontSize = 18.sp)
        )
        BasicText(scope, style = IronLemonType.Body.copy(color = IronLemonColors.Lemon, fontSize = 13.sp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(IronLemonColors.Outline))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Every value is read again after a change: one setting can change what another shows.
            val token = tick + version
            settings.forEachIndexed { index, setting ->
                val modifier = if (index == 0) Modifier.focusRequester(first) else Modifier
                when (setting) {
                    is QuickSetting.Toggle -> ToggleRow(setting, token, modifier, changed)
                    is QuickSetting.Percent -> if (remember(token) { setting.visible() }) {
                        PercentRow(setting, token, changed)
                    }
                    is QuickSetting.Choice -> ChoiceRow(setting, token, changed)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    setting: QuickSetting.Toggle,
    token: Int,
    modifier: Modifier,
    onChanged: () -> Unit
) {
    val checked = remember(token) { setting.get() }
    val enabled = remember(token) { setting.enabled() }
    Box(modifier.alpha(if (enabled) 1f else 0.4f)) {
        IronLemonSwitchRow(stringResource(setting.titleRes), checked) { value ->
            if (enabled) {
                setting.set(value)
                onChanged()
            }
        }
    }
}

@Composable
private fun PercentRow(setting: QuickSetting.Percent, token: Int, onChanged: () -> Unit) {
    val steps = (setting.max - setting.min) / setting.step
    var step by remember(token) {
        mutableIntStateOf(((setting.get() - setting.min) / setting.step).coerceIn(0, steps))
    }
    SettingCard {
        BasicText(
            stringResource(R.string.quick_settings_value, stringResource(setting.titleRes),
                setting.min + step * setting.step),
            style = IronLemonType.Button
        )
        IronLemonSlider(step, steps, { value ->
            step = value
            setting.set(setting.min + value * setting.step)
            onChanged()
        })
    }
}

@Composable
private fun ChoiceRow(setting: QuickSetting.Choice, token: Int, onChanged: () -> Unit) {
    val names = stringArrayResource(setting.namesRes)
    val current = remember(token) { setting.values.indexOf(setting.get()) }
    SettingCard {
        BasicText(stringResource(setting.titleRes), style = IronLemonType.Button)
        names.toList().chunked(3).forEachIndexed { row, chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chunk.forEachIndexed { column, name ->
                    val index = row * 3 + column
                    Chip(name, index == current, Modifier.weight(1f)) {
                        setting.set(setting.values[index])
                        onChanged()
                    }
                }
                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(IronLemonColors.Surface)
            .border(1.dp, IronLemonColors.Outline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .heightIn(min = 38.dp)
            .lemonInteractive(shape = shape, cornerRadius = 12.dp, selected = selected, onClick = onClick)
            .clip(shape)
            .background(if (selected) IronLemonColors.Lemon else IronLemonColors.SurfaceRaised)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text,
            style = IronLemonType.Button.copy(
                fontSize = 12.sp,
                color = if (selected) IronLemonColors.OnLemon else IronLemonColors.Text,
                textAlign = TextAlign.Center
            ),
            maxLines = 2
        )
    }
}
