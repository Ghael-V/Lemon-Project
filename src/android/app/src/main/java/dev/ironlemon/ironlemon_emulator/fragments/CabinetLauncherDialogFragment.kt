// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.fragment.findNavController
import dev.ironlemon.ironlemon_emulator.HomeNavigationDirections
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.AppletInfo
import dev.ironlemon.ironlemon_emulator.model.CabinetMode
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonActionCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard

/** The amiibo settings applet (Cabinet) opens in one of its modes. */
class CabinetLauncherDialogFragment : IronLemonComposeDialogFragment() {
    @Composable
    override fun DialogContent() {
        IronLemonDialogCard(title = stringResource(R.string.cabinet_launcher)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CabinetMode.entries.filter { it != CabinetMode.None }.forEach { mode ->
                    IronLemonActionCard(
                        title = stringResource(mode.titleId),
                        description = null,
                        iconRes = mode.iconId,
                        onClick = { launch(mode) }
                    )
                }
            }
        }
    }

    private fun launch(mode: CabinetMode) {
        val appletPath = NativeLibrary.getAppletLaunchPath(AppletInfo.Cabinet.entryId)
        NativeLibrary.setCurrentAppletId(AppletInfo.Cabinet.appletId)
        NativeLibrary.setCabinetMode(mode.id)
        val appletGame = Game(title = getString(R.string.cabinet_applet), path = appletPath)
        findNavController().navigate(HomeNavigationDirections.actionGlobalEmulationActivity(appletGame))
    }
}
