// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.content.DialogInterface
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText

/**
 * An error from the core while a game runs: go on or stop. The core thread waits on
 * [NativeLibrary.coreErrorAlertLock] for the answer; closing the dialog any other way goes on.
 */
class CoreErrorDialogFragment : IronLemonComposeDialogFragment() {
    private var aborted = false

    @Composable
    override fun DialogContent() {
        IronLemonDialogCard(
            title = requireArguments().getString(TITLE),
            buttons = {
                IronLemonButton(
                    text = stringResource(R.string.abort_button),
                    height = 44.dp,
                    onClick = {
                        aborted = true
                        dismiss()
                    }
                )
                IronLemonButton(
                    text = stringResource(R.string.continue_button),
                    primary = true,
                    height = 44.dp,
                    onClick = ::dismiss
                )
            }
        ) {
            IronLemonDialogText(requireArguments().getString(MESSAGE).orEmpty())
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        NativeLibrary.coreErrorAlertResult = !aborted
        synchronized(NativeLibrary.coreErrorAlertLock) { NativeLibrary.coreErrorAlertLock.notify() }
    }

    companion object {
        const val TITLE = "Title"
        const val MESSAGE = "Message"

        fun newInstance(title: String, message: String): CoreErrorDialogFragment {
            val frag = CoreErrorDialogFragment()
            val args = Bundle()
            args.putString(TITLE, title)
            args.putString(MESSAGE, message)
            frag.arguments = args
            return frag
        }
    }
}
