// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.applets.keyboard.ui

import android.content.DialogInterface
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.applets.keyboard.SoftwareKeyboard
import dev.ironlemon.ironlemon_emulator.applets.keyboard.SoftwareKeyboard.KeyboardConfig
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonColors
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonType
import dev.ironlemon.ironlemon_emulator.utils.SerializableHelper.serializable

/**
 * The text a game asks for with the system's software keyboard applet. The game waits on
 * [SoftwareKeyboard.dataLock] until this closes; Cancel and closing it any other way cancel.
 */
class KeyboardDialogFragment : IronLemonComposeDialogFragment() {
    private lateinit var config: KeyboardConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = requireArguments().serializable(CONFIG)!!
        SoftwareKeyboard.data.result = SoftwareKeyboard.SwkbdResult.Cancel.ordinal
    }

    @Composable
    override fun DialogContent() {
        var text by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        val password = config.password_mode == SoftwareKeyboard.SwkbdPasswordMode.Enabled.ordinal
        val numbers = config.type == SoftwareKeyboard.SwkbdType.NumberPad.ordinal
        val keyboardType = when {
            numbers && password -> KeyboardType.NumberPassword
            numbers -> KeyboardType.Number
            password -> KeyboardType.Password
            else -> KeyboardType.Text
        }
        IronLemonDialogCard(
            title = config.header_text?.ifEmpty { null } ?: stringResource(R.string.software_keyboard),
            buttons = {
                IronLemonButton(
                    text = stringResource(android.R.string.cancel),
                    height = 44.dp,
                    onClick = ::dismiss
                )
                IronLemonButton(
                    text = config.ok_text?.ifEmpty { null } ?: stringResource(R.string.submit),
                    primary = true,
                    height = 44.dp,
                    onClick = {
                        SoftwareKeyboard.data.result = SoftwareKeyboard.SwkbdResult.Ok.ordinal
                        SoftwareKeyboard.data.text = text
                        dismiss()
                    }
                )
            }
        ) {
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(IronLemonColors.Surface)
                    .border(1.dp, IronLemonColors.Outline, shape)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                // What the game suggests, shown until something is typed.
                if (text.isEmpty() && !config.initial_text.isNullOrEmpty()) {
                    BasicText(config.initial_text!!, style = IronLemonType.Body)
                }
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = if (config.max_text_length > 0) it.take(config.max_text_length) else it
                    },
                    singleLine = !config.enable_return_button,
                    textStyle = IronLemonType.Button,
                    cursorBrush = SolidColor(IronLemonColors.Lemon),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation = if (password) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        synchronized(SoftwareKeyboard.dataLock) {
            SoftwareKeyboard.dataLock.notifyAll()
        }
    }

    companion object {
        const val TAG = "KeyboardDialogFragment"
        const val CONFIG = "keyboard_config"

        fun newInstance(config: KeyboardConfig?): KeyboardDialogFragment {
            val frag = KeyboardDialogFragment()
            val args = Bundle()
            args.putSerializable(CONFIG, config)
            frag.arguments = args
            return frag
        }
    }
}
