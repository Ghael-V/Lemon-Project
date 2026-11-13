// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.views

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.drawerlayout.widget.DrawerLayout
import dev.ironlemon.ironlemon_emulator.utils.Log

/**
 * DrawerLayout that survives AndroidX's ViewDragHelper losing the dragged drawer mid-gesture: it
 * then reports the release with a null view, and DrawerLayout's onViewReleased threw a
 * NullPointerException that closed the game (seen while dragging the in-game menu). That one
 * touch event is dropped instead; the next drag that grabs a drawer starts clean.
 */
class SafeDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DrawerLayout(context, attrs, defStyleAttr) {
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean =
        try {
            super.onTouchEvent(ev)
        } catch (e: NullPointerException) {
            Log.warning("[SafeDrawerLayout] Dropped a touch the drawer lost track of: ${e.message}")
            true
        }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        try {
            super.onInterceptTouchEvent(ev)
        } catch (e: NullPointerException) {
            Log.warning("[SafeDrawerLayout] Dropped a touch the drawer lost track of: ${e.message}")
            false
        }
}
