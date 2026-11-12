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
                horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End)
            ) { buttons() }
        }
    }
}
