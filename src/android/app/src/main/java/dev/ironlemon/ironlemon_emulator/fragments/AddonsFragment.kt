// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.navArgs
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.AddonViewModel
import dev.ironlemon.ironlemon_emulator.model.Patch
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonActionCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonCardGrid
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonIconButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonPage
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonSwitch
import dev.ironlemon.ironlemon_emulator.utils.AddonUtil
import dev.ironlemon.ironlemon_emulator.utils.FileUtil.copyFilesTo
import dev.ironlemon.ironlemon_emulator.utils.collect
import java.io.File

class AddonsFragment : IronLemonComposeFragment() {
    private val addonViewModel: AddonViewModel by activityViewModels()

    private val args by navArgs<AddonsFragmentArgs>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addonViewModel.onOpenAddons(args.game)
    }

    @Composable
    override fun Content() {
        val addons by addonViewModel.addonList.collectAsState()
        IronLemonPage(
            title = stringResource(R.string.addons_game, args.game.title),
            onBack = ::goBack
        ) {
            Box(Modifier.fillMaxSize()) {
                IronLemonCardGrid(bottom = 112.dp) {
                    items(addons) { addon -> AddonCard(addon) }
                }
                IronLemonButton(
                    text = stringResource(R.string.install),
                    iconRes = R.drawable.ic_add,
                    primary = true,
                    height = 56.dp,
                    onClick = {
                        ContentTypeSelectionDialogFragment().show(
                            parentFragmentManager,
                            ContentTypeSelectionDialogFragment.TAG
                        )
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
                )
            }
        }
    }

    /** An add-on: tap or switch to enable it (saved when the screen closes), or delete it. */
    @Composable
    private fun AddonCard(addon: Patch) {
        var enabled by remember(addon) { mutableStateOf(addon.enabled) }
        val toggle = {
            enabled = !enabled
            addon.enabled = enabled
        }
        IronLemonActionCard(
            title = addon.name,
            description = addon.version,
            selected = enabled,
            onClick = toggle
        ) {
            IronLemonSwitch(checked = enabled)
            IronLemonIconButton(
                iconRes = R.drawable.ic_delete,
                description = stringResource(R.string.delete),
                onClick = { addonViewModel.setAddonToDelete(addon) }
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        addonViewModel.showModInstallPicker.collect(
            viewLifecycleOwner,
            resetState = { addonViewModel.showModInstallPicker(false) }
        ) { if (it) installAddon.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data) }
        addonViewModel.showModNoticeDialog.collect(
            viewLifecycleOwner,
            resetState = { addonViewModel.showModNoticeDialog(false) }
        ) {
            if (it) {
                MessageDialogFragment.newInstance(
                    requireActivity(),
                    titleId = R.string.addon_notice,
                    descriptionId = R.string.addon_notice_description,
                    dismissible = false,
                    positiveAction = { addonViewModel.showModInstallPicker(true) },
                    negativeAction = {},
                    negativeButtonTitleId = R.string.close
                ).show(parentFragmentManager, MessageDialogFragment.TAG)
            }
        }
        addonViewModel.addonToDelete.collect(
            viewLifecycleOwner,
            resetState = { addonViewModel.setAddonToDelete(null) }
        ) {
            if (it != null) {
                MessageDialogFragment.newInstance(
                    requireActivity(),
                    titleId = R.string.confirm_uninstall,
                    descriptionId = R.string.confirm_uninstall_description,
                    positiveAction = { addonViewModel.onDeleteAddon(it) },
                    negativeAction = {}
                ).show(parentFragmentManager, MessageDialogFragment.TAG)
            }
        }

    }

    override fun onResume() {
        super.onResume()
        addonViewModel.refreshAddons()
    }

    override fun onDestroy() {
        super.onDestroy()
        addonViewModel.onCloseAddons()
    }

    val installAddon =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { result ->
            if (result == null) {
                return@registerForActivityResult
            }

            val externalAddonDirectory = DocumentFile.fromTreeUri(requireContext(), result)
            if (externalAddonDirectory == null) {
                MessageDialogFragment.newInstance(
                    requireActivity(),
                    titleId = R.string.invalid_directory,
                    descriptionId = R.string.invalid_directory_description
                ).show(parentFragmentManager, MessageDialogFragment.TAG)
                return@registerForActivityResult
            }

            val isValid = externalAddonDirectory.listFiles()
                .any { AddonUtil.validAddonDirectories.contains(it.name?.lowercase()) }
            val errorMessage = MessageDialogFragment.newInstance(
                requireActivity(),
                titleId = R.string.invalid_directory,
                descriptionId = R.string.invalid_directory_description
            )
            if (isValid) {
                ProgressDialogFragment.newInstance(
                    requireActivity(),
                    R.string.installing_game_content,
                    false
                ) { progressCallback, _ ->
                    val parentDirectoryName = externalAddonDirectory.name
                    val internalAddonDirectory =
                        File(args.game.addonDir + parentDirectoryName)
                    try {
                        externalAddonDirectory.copyFilesTo(internalAddonDirectory, progressCallback)
                    } catch (_: Exception) {
                        return@newInstance errorMessage
                    }
                    addonViewModel.refreshAddons()
                    return@newInstance getString(R.string.addon_installed_successfully)
                }.show(parentFragmentManager, ProgressDialogFragment.TAG)
            } else {
                errorMessage.show(parentFragmentManager, MessageDialogFragment.TAG)
            }
        }
}
