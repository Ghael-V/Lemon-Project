// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.features.settings.model.view.DateTimeSetting
import dev.lemon.lemon_emu.features.settings.model.view.IntSingleChoiceSetting
import dev.lemon.lemon_emu.features.settings.model.view.LaunchableSetting
import dev.lemon.lemon_emu.features.settings.model.view.PathSetting
import dev.lemon.lemon_emu.features.settings.model.view.RunnableSetting
import dev.lemon.lemon_emu.features.settings.model.view.SettingsItem
import dev.lemon.lemon_emu.features.settings.model.view.SingleChoiceSetting
import dev.lemon.lemon_emu.features.settings.model.view.SliderSetting
import dev.lemon.lemon_emu.features.settings.model.view.SpinBoxSetting
import dev.lemon.lemon_emu.features.settings.model.view.StringInputSetting
import dev.lemon.lemon_emu.features.settings.model.view.StringSingleChoiceSetting
import dev.lemon.lemon_emu.features.settings.model.view.SubmenuSetting
import dev.lemon.lemon_emu.features.settings.model.view.SwitchSetting
import dev.lemon.lemon_emu.model.HomeSetting
import dev.lemon.lemon_emu.utils.PathUtil
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Settings home
// ---------------------------------------------------------------------------------------------

/** The settings home: a searchable grid of category cards. */
@Composable
fun ModernHomeSettings(
    options: List<HomeSetting>,
    onOptionClick: (HomeSetting) -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(options, query) {
        val term = query.trim().lowercase(Locale.getDefault())
        if (term.isEmpty()) {
            options
        } else {
            options.filter {
                context.getString(it.titleId).lowercase(Locale.getDefault()).contains(term) ||
                    context.getString(it.descriptionId).lowercase(Locale.getDefault()).contains(term)
            }
        }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(Modifier.fillMaxSize().background(LemonColors.Background).padding(horizontal = 24.dp)) {
        SearchBox(
            value = query,
            onValueChange = { query = it },
            hint = stringResource(R.string.lemon_search_settings),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (landscape) 3 else 1),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(shown) { option -> HomeCard(option, onOptionClick) }
        }
    }
}

@Composable
private fun HomeCard(option: HomeSetting, onClick: (HomeSetting) -> Unit) {
    val enabled = option.isEnabled()
    val details by option.details.collectAsState()
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .lemonInteractive(
                shape = shape,
                cornerRadius = 22.dp,
                glowColor = LemonColors.Lemon,
                focusScale = 1.03f,
                pressScale = 0.96f,
                onClick = { onClick(option) }
            )
            .clip(shape)
            .background(LemonColors.Surface)
            .padding(18.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Image(
            painter = painterResource(option.iconId),
            contentDescription = null,
            colorFilter = ColorFilter.tint(LemonColors.Lemon),
            modifier = Modifier.size(26.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(stringResource(option.titleId), style = LemonType.Button.copy(fontSize = 16.sp))
            BasicText(
                stringResource(option.descriptionId),
                style = LemonType.Body.copy(fontSize = 13.sp),
                maxLines = 2
            )
            if (details.isNotEmpty()) {
                BasicText(details, style = LemonType.Label.copy(letterSpacing = 0.sp, fontSize = 12.sp), maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A section of settings
// ---------------------------------------------------------------------------------------------

/** What tapping a settings row does: the classic adapter's own handlers, so behavior is unchanged. */
class SettingsActions(
    val onSwitch: (SwitchSetting, Boolean, Int) -> Unit,
    val onSingleChoice: (SingleChoiceSetting, Int) -> Unit,
    val onStringSingleChoice: (StringSingleChoiceSetting, Int) -> Unit,
    val onIntSingleChoice: (IntSingleChoiceSetting, Int) -> Unit,
    val onSlider: (SliderSetting, Int) -> Unit,
    val onSpinBox: (SpinBoxSetting, Int) -> Unit,
    val onDateTime: (DateTimeSetting, Int) -> Unit,
    val onStringInput: (StringInputSetting, Int) -> Unit,
    val onPath: (PathSetting, Int) -> Unit,
    val onSubmenu: (SubmenuSetting) -> Unit,
    val onLaunchable: (LaunchableSetting) -> Unit,
    val onClear: (SettingsItem, Int) -> Unit,
    val onLongClick: (SettingsItem, Int) -> Unit,
    val onRailSection: (SubmenuSetting) -> Unit
)

/** The row types this screen can draw; a section with any other type keeps the classic list. */
val ModernSupportedTypes = setOf(
    SettingsItem.TYPE_HEADER,
    SettingsItem.TYPE_SWITCH,
    SettingsItem.TYPE_SINGLE_CHOICE,
    SettingsItem.TYPE_STRING_SINGLE_CHOICE,
    SettingsItem.TYPE_INT_SINGLE_CHOICE,
    SettingsItem.TYPE_SLIDER,
    SettingsItem.TYPE_SPINBOX,
    SettingsItem.TYPE_SUBMENU,
    SettingsItem.TYPE_DATETIME_SETTING,
    SettingsItem.TYPE_RUNNABLE,
    SettingsItem.TYPE_STRING_INPUT,
    SettingsItem.TYPE_LAUNCHABLE,
    SettingsItem.TYPE_PATH
)

@Composable
fun ModernSettingsList(
    items: List<SettingsItem>,
    tick: Int,
    isRoot: Boolean,
    currentSection: String?,
    railSections: List<SubmenuSetting>,
    actions: SettingsActions
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showRail = landscape && !isRoot && railSections.isNotEmpty()

    Row(Modifier.fillMaxSize().background(LemonColors.Background)) {
        if (showRail) {
            Rail(railSections, currentSection, actions.onRailSection)
        }
        if (isRoot) {
            SectionGrid(items, actions)
        } else {
            SectionList(items, tick, actions, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Rail(sections: List<SubmenuSetting>, current: String?, onClick: (SubmenuSetting) -> Unit) {
    LazyColumn(
        Modifier
            .width(230.dp)
            .fillMaxHeight()
            .background(LemonColors.Surface.copy(alpha = 0.6f)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(sections, key = { it.menuKey.name }) { section ->
            val selected = section.menuKey.name == current
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .lemonInteractive(
                        shape = shape,
                        cornerRadius = 14.dp,
                        focusScale = 1.02f,
                        pressScale = 0.97f,
                        onClick = { onClick(section) }
                    )
                    .clip(shape)
                    .background(if (selected) LemonColors.Lemon else LemonColors.Background.copy(alpha = 0f))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (section.iconId != 0) {
                    Image(
                        painter = painterResource(section.iconId),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(if (selected) LemonColors.OnLemon else LemonColors.TextMuted),
                        modifier = Modifier.size(18.dp)
                    )
                }
                BasicText(
                    section.title,
                    style = LemonType.Button.copy(
                        fontSize = 14.sp,
                        color = if (selected) LemonColors.OnLemon else LemonColors.Text
                    ),
                    maxLines = 2,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
        }
    }
}

/** The root of the settings: its sections as big cards. */
@Composable
private fun SectionGrid(items: List<SettingsItem>, actions: SettingsActions) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (landscape) 2 else 1),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items.size) { index ->
            val item = items[index]
            val shape = RoundedCornerShape(22.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .lemonInteractive(
                        shape = shape,
                        cornerRadius = 22.dp,
                        focusScale = 1.03f,
                        pressScale = 0.96f,
                        onClick = { activate(item, index, actions) }
                    )
                    .clip(shape)
                    .background(LemonColors.Surface)
                    .padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val icon = (item as? SubmenuSetting)?.iconId ?: (item as? RunnableSetting)?.iconId ?: 0
                if (icon != 0) {
                    Image(
                        painter = painterResource(icon),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(LemonColors.Lemon),
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(item.title, style = LemonType.Button.copy(fontSize = 16.sp))
                    if (item.description.isNotEmpty()) {
                        BasicText(item.description, style = LemonType.Body.copy(fontSize = 13.sp), maxLines = 2)
                    }
                }
            }
        }
    }
}

private fun activate(item: SettingsItem, index: Int, actions: SettingsActions) {
    when (item) {
        is SubmenuSetting -> actions.onSubmenu(item)
        is RunnableSetting -> if (item.isRunnable) item.runnable.invoke()
        is LaunchableSetting -> actions.onLaunchable(item)
        else -> Unit
    }
}

/** One section: a title label per header, its rows together in one rounded card. */
@Composable
private fun SectionList(items: List<SettingsItem>, tick: Int, actions: SettingsActions, modifier: Modifier) {
    // Group the rows under their headers.
    val groups = remember(items, tick) {
        val out = mutableListOf<Pair<String?, MutableList<Pair<SettingsItem, Int>>>>()
        items.forEachIndexed { index, item ->
            if (item.type == SettingsItem.TYPE_HEADER) {
                out += item.title to mutableListOf()
            } else {
                if (out.isEmpty()) out += null to mutableListOf()
                out.last().second += item to index
            }
        }
        out.filter { it.second.isNotEmpty() }
    }
    LazyColumn(
        modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        groups.forEachIndexed { groupIndex, (header, rows) ->
            item(key = "header_$groupIndex") {
                if (header != null) {
                    BasicText(
                        header.uppercase(Locale.getDefault()),
                        style = LemonType.Label.copy(color = LemonColors.TextMuted),
                        modifier = Modifier.padding(start = 6.dp, top = if (groupIndex == 0) 4.dp else 18.dp, bottom = 4.dp)
                    )
                }
            }
            item(key = "group_$groupIndex") {
                val shape = RoundedCornerShape(22.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 820.dp)
                        .clip(shape)
                        .background(LemonColors.Surface)
                ) {
                    rows.forEachIndexed { rowIndex, (item, position) ->
                        if (rowIndex > 0) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(LemonColors.Outline.copy(alpha = 0.5f)))
                        }
                        SettingRow(item, position, tick, actions)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingRow(item: SettingsItem, position: Int, @Suppress("UNUSED_PARAMETER") tick: Int, actions: SettingsActions) {
    val context = LocalContext.current
    val enabled = item.isEditable
    val shape = RoundedCornerShape(0.dp)

    // What is shown on the right, and what a tap does.
    var value: String? = null
    var chevron = false
    var switchState: Boolean? = null
    var onClick: () -> Unit = {}
    var icon = 0

    when (item) {
        is SwitchSetting -> {
            switchState = item.getIsChecked(item.needsRuntimeGlobal)
            onClick = { actions.onSwitch(item, !switchState, position) }
        }
        is SingleChoiceSetting -> {
            val resources = context.resources
            val values = resources.getIntArray(item.valuesId)
            val index = values.indexOf(item.getSelectedValue())
            value = if (index >= 0) resources.getStringArray(item.choicesId)[index] else null
            chevron = true
            onClick = { actions.onSingleChoice(item, position) }
        }
        is StringSingleChoiceSetting -> {
            value = item.getSelectedValue()
            chevron = true
            onClick = { actions.onStringSingleChoice(item, position) }
        }
        is IntSingleChoiceSetting -> {
            value = item.getChoiceAt(item.getSelectedValue())
            chevron = true
            onClick = { actions.onIntSingleChoice(item, position) }
        }
        is SliderSetting -> {
            value = context.getString(R.string.value_with_units, item.getSelectedValue(), item.units)
            chevron = true
            onClick = { actions.onSlider(item, position) }
        }
        is SpinBoxSetting -> {
            value = item.getSelectedValue().toString()
            chevron = true
            onClick = { actions.onSpinBox(item, position) }
        }
        is DateTimeSetting -> {
            val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(item.getValue() * 1000), ZoneId.of("UTC"))
            value = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(time)
            chevron = true
            onClick = { actions.onDateTime(item, position) }
        }
        is StringInputSetting -> {
            value = item.getSelectedValue()
            chevron = true
            onClick = { actions.onStringInput(item, position) }
        }
        is PathSetting -> {
            value = if (item.isUsingDefaultPath()) {
                context.getString(R.string.default_string)
            } else {
                PathUtil.truncatePathForDisplay(item.getCurrentPath())
            }
            icon = item.iconId
            chevron = true
            onClick = { actions.onPath(item, position) }
        }
        is SubmenuSetting -> {
            icon = item.iconId
            chevron = true
            onClick = { actions.onSubmenu(item) }
        }
        is RunnableSetting -> {
            icon = item.iconId
            onClick = { if (item.isRunnable) item.runnable.invoke() }
        }
        is LaunchableSetting -> {
            chevron = true
            onClick = { actions.onLaunchable(item) }
        }
        else -> Unit
    }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .lemonInteractive(
                shape = shape,
                cornerRadius = 0.dp,
                focusScale = 1.0f,
                pressScale = 0.985f,
                role = null,
                onLongClick = { actions.onLongClick(item, position) },
                onClick = { if (enabled) onClick() }
            )
            .background(LemonColors.Surface)
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (icon != 0) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(LemonColors.Lemon),
                modifier = Modifier.size(22.dp)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(item.title, style = LemonType.Button.copy(fontSize = 15.sp))
            if (item.description.isNotEmpty()) {
                BasicText(item.description, style = LemonType.Body.copy(fontSize = 13.sp), maxLines = 3)
            }
        }
        if (!value.isNullOrEmpty()) {
            BasicText(
                value,
                style = LemonType.Button.copy(fontSize = 14.sp, color = LemonColors.Lemon),
                maxLines = 1,
                modifier = Modifier.widthIn(max = 220.dp)
            )
        }
        if (switchState != null) {
            LemonSwitch(switchState)
        }
        if (chevron) {
            Image(
                painter = painterResource(R.drawable.ic_arrow_forward),
                contentDescription = null,
                colorFilter = ColorFilter.tint(LemonColors.TextMuted),
                modifier = Modifier.size(16.dp)
            )
        }
        if (item.clearable) {
            Image(
                painter = painterResource(R.drawable.ic_clear),
                contentDescription = stringResource(R.string.clear),
                colorFilter = ColorFilter.tint(LemonColors.TextMuted),
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .lemonInteractive(
                        shape = CircleShape,
                        cornerRadius = 16.dp,
                        role = null,
                        onClick = { actions.onClear(item, position) }
                    )
                    .padding(6.dp)
            )
        }
    }
}


@Composable
private fun LemonSwitch(checked: Boolean) {
    val thumb by animateDpAsState(
        targetValue = if (checked) 24.dp else 4.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "switchThumb"
    )
    val track by animateColorAsState(
        targetValue = if (checked) LemonColors.Lemon else LemonColors.SurfaceRaised,
        label = "switchTrack"
    )
    Box(
        Modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(CircleShape)
            .background(track)
            .then(if (checked) Modifier else Modifier.border(1.dp, LemonColors.Outline, CircleShape))
    ) {
        Box(
            Modifier
                .offset(x = thumb, y = 4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) LemonColors.OnLemon else LemonColors.TextMuted)
        )
    }
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(LemonColors.SurfaceRaised.copy(alpha = 0.8f))
            .border(1.dp, LemonColors.Outline, shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            colorFilter = ColorFilter.tint(LemonColors.TextMuted),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = LemonType.Button.copy(fontSize = 15.sp),
            cursorBrush = SolidColor(LemonColors.Lemon),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) BasicText(hint, style = LemonType.Body)
                    inner()
                }
            }
        )
    }
}
