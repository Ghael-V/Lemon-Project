// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.ui.modern

import android.app.Dialog
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import dev.ironlemon.ironlemon_emulator.utils.FullscreenHelper

/**
 * A dialog drawn in Compose: a transparent, dimmed window without the system bars, holding
 * [DialogContent]. [widthFraction] / [heightFraction] size the window to the screen; null wraps the
 * content.
 */
abstract class IronLemonComposeDialogFragment : DialogFragment() {
    protected open val widthFraction: Float? = null
    protected open val heightFraction: Float? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply {
            window?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val metrics = resources.displayMetrics
            window.setLayout(
                widthFraction?.let { (metrics.widthPixels * it).toInt() }
                    ?: WindowManager.LayoutParams.WRAP_CONTENT,
                heightFraction?.let { (metrics.heightPixels * it).toInt() }
                    ?: WindowManager.LayoutParams.WRAP_CONTENT
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.6f)
            FullscreenHelper.hideSystemBars(window)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { DialogContent() }
    }

    @Composable
    protected abstract fun DialogContent()
}

/** A bar filled to [fraction] with the accent; null: not known yet, a pulsing full bar. */
@Composable
fun IronLemonProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "progress")
    val alpha by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(IronLemonColors.SurfaceRaised)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction ?: 1f)
                .height(8.dp)
                .background(if (fraction == null) IronLemonColors.gradient(alpha) else IronLemonColors.Gradient)
        )
    }
}

/** The card a dialog is drawn on: the app's dark surface with the accent border. */
@Composable
fun IronLemonDialogCard(
    title: String?,
    modifier: Modifier = Modifier,
    buttons: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .widthIn(min = 320.dp, max = 640.dp)
            .clip(shape)
            .background(IronLemonColors.Background)
            .border(1.dp, IronLemonColors.gradient(0.5f), shape)
            .padding(28.dp)
    ) {
        if (!title.isNullOrEmpty()) {
            BasicText(title, style = IronLemonType.Heading)
            Spacer(Modifier.height(12.dp))
        }
        content()
        if (buttons != null) {
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) { buttons() }
        }
    }
}

/** A dialog's message text, scrolling when long. */
@Composable
fun IronLemonDialogText(text: CharSequence) {
    val annotated = text as? androidx.compose.ui.text.AnnotatedString
        ?: androidx.compose.ui.text.AnnotatedString(text.toString())
    BasicText(
        annotated,
        style = IronLemonType.Body.copy(color = IronLemonColors.Text),
        modifier = Modifier
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
    )
}

/** Options to pick one of, each a row with a selection ring, the picked one marked. */
@Composable
fun IronLemonChoiceList(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .lemonInteractive(
                        shape = shape,
                        cornerRadius = 18.dp,
                        selected = isSelected,
                        focusScale = 1.02f,
                        onClick = { onSelect(index) }
                    )
                    .clip(shape)
                    .background(IronLemonColors.Surface)
                    .border(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) IronLemonColors.Gradient else SolidColor(IronLemonColors.Outline),
                        shape
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IronLemonRadio(isSelected)
                Spacer(Modifier.width(16.dp))
                BasicText(option, style = IronLemonType.Button)
            }
        }
    }
}

/** The selection ring used by option lists and the driver list. */
@Composable
fun IronLemonRadio(selected: Boolean) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(2.dp, if (selected) IronLemonColors.Orange else IronLemonColors.TextMuted, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(IronLemonColors.Gradient))
        }
    }
}

/** A setting that is on or off: its label and the switch, the whole row toggling it. */
@Composable
fun IronLemonSwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .lemonInteractive(
                shape = shape,
                cornerRadius = 18.dp,
                focusScale = 1.02f,
                onClick = { onToggle(!checked) }
            )
            .clip(shape)
            .background(IronLemonColors.Surface)
            .border(1.dp, IronLemonColors.Outline, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(label, style = IronLemonType.Button, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        IronLemonSwitch(checked)
    }
}

/** A one-line text field on the app's surface. */
@Composable
fun IronLemonTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = IronLemonType.Button,
        cursorBrush = SolidColor(IronLemonColors.Lemon),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(IronLemonColors.Surface)
            .border(1.dp, IronLemonColors.Outline, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    )
}
