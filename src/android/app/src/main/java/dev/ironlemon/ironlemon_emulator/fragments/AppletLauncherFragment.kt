// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.widget.Toast
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.findNavController
import dev.ironlemon.ironlemon_emulator.HomeNavigationDirections
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.Applet
import dev.ironlemon.ironlemon_emulator.model.AppletInfo
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonActionCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonCardGrid
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonPage

/** The system applets that can be started on their own, from the installed firmware. */
class AppletLauncherFragment : IronLemonComposeFragment() {
    private val applets = listOf(
        Applet(
            R.string.album_applet,
            R.string.album_applet_description,
            R.drawable.ic_album,
            AppletInfo.PhotoViewer
        ),
        Applet(
            R.string.cabinet_applet,
            R.string.cabinet_applet_description,
            R.drawable.ic_nfc,
            AppletInfo.Cabinet
        ),
        Applet(
            R.string.mii_edit_applet,
            R.string.mii_edit_applet_description,
            R.drawable.ic_mii,
            AppletInfo.MiiEdit
        )
    )

    @Composable
    override fun Content() {
        IronLemonPage(title = stringResource(R.string.applets), onBack = ::goBack) {
            IronLemonCardGrid {
                items(applets) { applet ->
                    IronLemonActionCard(
                        title = stringResource(applet.titleId),
                        description = stringResource(applet.descriptionId),
                        iconRes = applet.iconId,
                        onClick = { launch(applet) }
                    )
                }
            }
        }
    }

    private fun launch(applet: Applet) {
        val navController = view?.findNavController() ?: return
        val appletPath = NativeLibrary.getAppletLaunchPath(applet.appletInfo.entryId)
        if (appletPath.isEmpty()) {
            Toast.makeText(requireContext(), R.string.applets_error_applet, Toast.LENGTH_SHORT)
                .show()
            return
        }

        if (applet.appletInfo == AppletInfo.Cabinet) {
            navController.navigate(R.id.action_appletLauncherFragment_to_cabinetLauncherDialogFragment)
            return
        }

        NativeLibrary.setCurrentAppletId(applet.appletInfo.appletId)
        val appletGame = Game(title = getString(applet.titleId), path = appletPath)
        navController.navigate(HomeNavigationDirections.actionGlobalEmulationActivity(appletGame))
    }
}
