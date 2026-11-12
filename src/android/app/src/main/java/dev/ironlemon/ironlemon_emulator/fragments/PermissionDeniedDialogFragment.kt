// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText

/** A permission was refused for good: it can only be given in the system's app settings. */
class PermissionDeniedDialogFragment : IronLemonComposeDialogFragment() {
    @Composable
    override fun DialogContent() {
        IronLemonDialogCard(
            title = stringResource(R.string.permission_denied),
            buttons = {
                IronLemonButton(
                    text = stringResource(android.R.string.cancel),
                    height = 44.dp,
                    onClick = ::dismiss
                )
                IronLemonButton(
                    text = stringResource(R.string.home_settings),
                    primary = true,
                    height = 44.dp,
                    onClick = {
                        openSettings()
                        dismiss()
                    }
                )
            }
        ) {
            IronLemonDialogText(stringResource(R.string.permission_denied_description))
        }
    }

    private fun openSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", requireActivity().packageName, null)
        startActivity(intent)
    }

    companion object {
        const val TAG = "PermissionDeniedDialogFragment"
    }
}
