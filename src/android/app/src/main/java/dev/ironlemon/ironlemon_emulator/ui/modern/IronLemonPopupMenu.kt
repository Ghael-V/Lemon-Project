// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.ui.modern

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.utils.FullscreenHelper

/**
 * Shows [content] in a dialog of its own, with no fragment behind it (for code that only has a
 * context: a menu, an alert from the core). [content] gets a function that closes the dialog;
 * [onDismiss] runs however it closes.
 */
fun showIronLemonDialog(
    context: Context,
    cancelable: Boolean = true,
    onDismiss: () -> Unit = {},
    content: @Composable (dismiss: () -> Unit) -> Unit
): ComponentDialog {
    val dialog = ComponentDialog(context)
    dialog.setCancelable(cancelable)
    dialog.setCanceledOnTouchOutside(cancelable)
    dialog.setContentView(
        ComposeView(context).apply {
            setContent { content(dialog::dismiss) }
        }
    )
    dialog.setOnDismissListener { onDismiss() }
    dialog.window?.let { window ->
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.6f)
    }
    dialog.show()
    dialog.window?.let { FullscreenHelper.hideSystemBars(it) }
    return dialog
}

/**
 * A drop-in for [PopupMenu] (same menuInflater / menu / listeners / show) that shows the menu's
 * visible items as Iron Lemon option rows: a switch on the checkable ones. Picking one runs the
 * click listener and closes it, as a popup menu does.
 */
class IronLemonPopupMenu(private val context: Context, anchor: View) {
    // Only holds the menu: the items are inflated and set up through it as before.
    private val source = PopupMenu(context, anchor)

    val menu: Menu get() = source.menu
    val menuInflater: MenuInflater get() = source.menuInflater

    private var dismissListener: (() -> Unit)? = null

    fun setOnMenuItemClickListener(listener: (MenuItem) -> Boolean) {
        source.setOnMenuItemClickListener { listener(it) }
    }

    fun setOnDismissListener(listener: () -> Unit) {
        dismissListener = listener
    }

    fun show() {
        showIronLemonDialog(context, onDismiss = { dismissListener?.invoke() }) { dismiss ->
            val items = (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible }
            IronLemonDialogCard(title = null) {
                Column(
                    Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items.forEach { item ->
                        val pick = {
                            if (item.isEnabled) {
                                // The item's own listener first, then the menu's, as a popup does.
                                menu.performIdentifierAction(item.itemId, 0)
                                dismiss()
                            }
                        }
                        if (item.isCheckable) {
                            IronLemonSwitchRow(item.title.toString(), item.isChecked) { pick() }
                        } else {
                            IronLemonActionCard(
                                title = item.title.toString(),
                                description = null,
                                onClick = pick
                            )
                        }
                    }
                }
            }
        }
    }
}
