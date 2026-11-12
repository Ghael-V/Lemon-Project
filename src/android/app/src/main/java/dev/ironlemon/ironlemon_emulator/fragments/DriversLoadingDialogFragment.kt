// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.activityViewModels
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.DriverViewModel
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonProgressBar
import dev.ironlemon.ironlemon_emulator.utils.collect

/** Shown while the driver list is being read or drivers are being deleted. */
class DriversLoadingDialogFragment : IronLemonComposeDialogFragment() {
    private val driverViewModel: DriverViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    @Composable
    override fun DialogContent() {
        IronLemonDialogCard(title = stringResource(R.string.loading)) {
            IronLemonProgressBar(null)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        driverViewModel.isInteractionAllowed.collect(viewLifecycleOwner) { if (it) dismiss() }
    }

    companion object {
        const val TAG = "DriversLoadingDialogFragment"
    }
}
