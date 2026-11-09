// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.overlay

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.overlay.model.OverlayControlData
import dev.lemon.lemon_emu.overlay.model.OverlayPressMode

class OverlayScaleDialog(
    context: Context,
    private val overlayControlData: OverlayControlData,
    /** Given for plain buttons only: the d-pad and the sticks have no press modes. */
    private val onPressModeChanged: ((Int) -> Unit)? = null,
    private val onScaleChanged: (Float) -> Unit
) : Dialog(context) {

    private var currentScale = overlayControlData.individualScale
    private val originalScale = overlayControlData.individualScale
    private var currentPressMode = overlayControlData.pressMode
    private val originalPressMode = overlayControlData.pressMode
    private lateinit var scaleValueText: TextView
    private lateinit var scaleSlider: Slider

    init {
        setupDialog()
    }

    private fun setupDialog() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_overlay_scale, null)
        setContentView(view)

        window?.setBackgroundDrawable(null)

        window?.apply {
            attributes = attributes.apply {
                flags = flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            }
        }

        scaleValueText = view.findViewById(R.id.scaleValueText)
        scaleSlider = view.findViewById(R.id.scaleSlider)
        val resetButton = view.findViewById<MaterialButton>(R.id.resetButton)
        val confirmButton = view.findViewById<MaterialButton>(R.id.confirmButton)
        val cancelButton = view.findViewById<MaterialButton>(R.id.cancelButton)

        scaleValueText.text = String.format("%.1fx",  currentScale)
        scaleSlider.value = currentScale

        scaleSlider.addOnChangeListener { _, value, input ->
            if (input) {
                currentScale = value
                scaleValueText.text = String.format("%.1fx", currentScale)
            }
        }

        scaleSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                // pass
            }

            override fun onStopTrackingTouch(slider: Slider) {
                onScaleChanged(currentScale)
            }
        })

        setupPressModes(view)

        resetButton.setOnClickListener {
            currentScale = 1.0f
            scaleSlider.value = 1.0f
            scaleValueText.text = String.format("%.1fx", currentScale)
            onScaleChanged(currentScale)
        }

        confirmButton.setOnClickListener {
            overlayControlData.individualScale = currentScale
            overlayControlData.pressMode = currentPressMode
            //slider value is already saved on touch dispatch but just to be sure
            onScaleChanged(currentScale)
            dismiss()
        }

        // both cancel button and back gesture should revert the changes
        cancelButton.setOnClickListener {
            revertChanges()
            dismiss()
        }

        setOnCancelListener {
            revertChanges()
            dismiss()
        }
    }

    private fun setupPressModes(view: View) {
        val onChanged = onPressModeChanged
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.pressModeGroup)
        if (onChanged == null) {
            view.findViewById<View>(R.id.pressModeLabel).visibility = View.GONE
            group.visibility = View.GONE
            return
        }

        group.check(
            when (currentPressMode) {
                OverlayPressMode.TOGGLE -> R.id.pressModeToggle
                OverlayPressMode.TURBO -> R.id.pressModeTurbo
                else -> R.id.pressModeNormal
            }
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentPressMode = when (checkedId) {
                    R.id.pressModeToggle -> OverlayPressMode.TOGGLE
                    R.id.pressModeTurbo -> OverlayPressMode.TURBO
                    else -> OverlayPressMode.NORMAL
                }
                onChanged(currentPressMode)
            }
        }
    }

    private fun revertChanges() {
        if (currentPressMode != originalPressMode) {
            onPressModeChanged?.invoke(originalPressMode)
        }
        onScaleChanged(originalScale)
    }

    fun showDialog(anchorX: Int, anchorY: Int, anchorHeight: Int, anchorWidth: Int) {
        show()

        show()

        // TODO: this calculation is a bit rough, improve it later on
        window?.let { window ->
            val layoutParams = window.attributes
            layoutParams.gravity = Gravity.TOP or Gravity.START

            val density = context.resources.displayMetrics.density
            val dialogWidthPx = (320 * density).toInt()
            val dialogHeightPx = (400 * density).toInt() // set your estimated dialog height

            val screenHeight = context.resources.displayMetrics.heightPixels


            layoutParams.x = anchorX + anchorWidth / 2 - dialogWidthPx / 2
            layoutParams.y = anchorY + anchorHeight / 2 - dialogHeightPx / 2
            layoutParams.width = dialogWidthPx


            window.attributes = layoutParams
        }

    }

}