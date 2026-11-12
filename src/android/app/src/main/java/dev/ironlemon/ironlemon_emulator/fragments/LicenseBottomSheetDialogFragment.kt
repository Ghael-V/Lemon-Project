// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.License
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonColors
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonType
import dev.ironlemon.ironlemon_emulator.utils.SerializableHelper.parcelable

/** A license in full: its project's link, the copyright line and the license text. */
class LicenseBottomSheetDialogFragment : IronLemonComposeDialogFragment() {
    override val widthFraction = 0.8f

    @Composable
    override fun DialogContent() {
        val license = requireArguments().parcelable<License>(LICENSE)!!
        IronLemonDialogCard(
            title = stringResource(license.titleId),
            buttons = {
                IronLemonButton(
                    text = stringResource(R.string.close),
                    primary = true,
                    height = 44.dp,
                    onClick = ::dismiss
                )
            }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                BasicText(stringResource(license.linkId), style = IronLemonType.Label)
                Spacer(Modifier.height(8.dp))
                BasicText(
                    stringResource(license.copyrightId),
                    style = IronLemonType.Body.copy(color = IronLemonColors.Text)
                )
                Spacer(Modifier.height(16.dp))
                BasicText(stringResource(license.licenseId), style = IronLemonType.Body)
            }
        }
    }

    companion object {
        const val TAG = "LicenseBottomSheetDialogFragment"

        const val LICENSE = "License"

        fun newInstance(license: License): LicenseBottomSheetDialogFragment {
            val dialog = LicenseBottomSheetDialogFragment()
            val bundle = Bundle()
            bundle.putParcelable(LICENSE, license)
            dialog.arguments = bundle
            return dialog
        }
    }
}
