// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.GamesViewModel
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonChoiceList
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText
import dev.ironlemon.ironlemon_emulator.ui.modern.showIronLemonDialog
import dev.ironlemon.ironlemon_emulator.utils.EmulatorMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "migrate from another emulator" dialogs, shared by every screen that offers it (setup
 * wizard, settings home, Install). Registers an activity result, so create it while the fragment
 * is being initialized, as a property. [onFinished] runs after a migration completes.
 */
class EmulatorMigrationFlow(
    private val fragment: Fragment,
    private val onFinished: () -> Unit = {}
) {
    // Set right before the folder picker opens; tells the result which emulator it belongs to.
    private var source: EmulatorMigration.Source? = null

    private val folderLauncher =
        fragment.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            val source = source
            if (treeUri != null && source != null) {
                onFolderPicked(source, treeUri)
            }
        }

    fun start() {
        val sources = EmulatorMigration.findSources(fragment.requireContext())
        when (sources.size) {
            0 -> showMessage(fragment.getString(R.string.migrate_no_sources))
            1 -> pickFolder(sources.first())
            else -> showIronLemonDialog(fragment.requireContext()) { dismiss ->
                IronLemonDialogCard(
                    title = stringResource(R.string.migrate_choose_source),
                    buttons = {
                        IronLemonButton(
                            text = stringResource(android.R.string.cancel),
                            height = 44.dp,
                            onClick = dismiss
                        )
                    }
                ) {
                    IronLemonChoiceList(sources.map { it.label }, -1) { which ->
                        dismiss()
                        pickFolder(sources[which])
                    }
                }
            }
        }
    }

    private fun pickFolder(source: EmulatorMigration.Source) {
        this.source = source
        // Opens straight on the emulator's user folder: the user only confirms access.
        folderLauncher.launch(source.initialUri)
    }

    private fun onFolderPicked(source: EmulatorMigration.Source, treeUri: Uri) {
        val context = fragment.requireContext().applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val plan = withContext(Dispatchers.IO) {
                EmulatorMigration.scan(context, source, treeUri)
            }
            if (plan == null) {
                showMessage(fragment.getString(R.string.migrate_wrong_folder, source.label))
                return@launch
            }
            if (plan.saves.isEmpty() && !plan.importKeys && !plan.importFirmware) {
                showMessage(fragment.getString(R.string.migrate_nothing, source.label))
                return@launch
            }

            val message = buildString {
                append(fragment.getString(R.string.migrate_found_saves, plan.saves.size))
                if (plan.conflicts > 0) {
                    append("\n")
                        .append(fragment.getString(R.string.migrate_found_conflicts, plan.conflicts))
                }
                if (plan.importKeys) {
                    append("\n").append(fragment.getString(R.string.migrate_found_keys))
                }
                if (plan.importFirmware) {
                    append("\n").append(fragment.getString(R.string.migrate_found_firmware))
                }
            }
            showIronLemonDialog(fragment.requireContext()) { dismiss ->
                IronLemonDialogCard(
                    title = stringResource(R.string.migrate_confirm_title, source.label),
                    buttons = {
                        IronLemonButton(
                            text = stringResource(android.R.string.cancel),
                            height = 44.dp,
                            onClick = dismiss
                        )
                        if (plan.conflicts > 0) {
                            IronLemonButton(
                                text = stringResource(R.string.migrate_keep),
                                height = 44.dp,
                                onClick = {
                                    dismiss()
                                    run(plan, false)
                                }
                            )
                            IronLemonButton(
                                text = stringResource(R.string.migrate_replace),
                                primary = true,
                                height = 44.dp,
                                onClick = {
                                    dismiss()
                                    run(plan, true)
                                }
                            )
                        } else {
                            IronLemonButton(
                                text = stringResource(R.string.migrate_import),
                                primary = true,
                                height = 44.dp,
                                onClick = {
                                    dismiss()
                                    run(plan, false)
                                }
                            )
                        }
                    }
                ) {
                    IronLemonDialogText(message)
                }
            }
        }
    }

    private fun run(plan: EmulatorMigration.Plan, replaceExisting: Boolean) {
        val activity = fragment.requireActivity()
        val context = activity.applicationContext
        val gamesViewModel = ViewModelProvider(activity)[GamesViewModel::class.java]
        ProgressDialogFragment.newInstance(activity, R.string.migrating) { progressCallback, _ ->
            val result = EmulatorMigration.migrate(context, plan, replaceExisting) { done, total ->
                progressCallback(total, done)
            }
            withContext(Dispatchers.Main) {
                if (result.keysImported || result.firmwareImported) {
                    gamesViewModel.reloadGames(true)
                }
                onFinished()
            }
            val summary = buildString {
                append(
                    context.getString(
                        R.string.migrate_done,
                        result.imported,
                        result.skipped,
                        result.failed
                    )
                )
                if (result.keysImported) {
                    append("\n").append(context.getString(R.string.migrate_done_keys))
                }
                if (result.firmwareImported) {
                    append("\n").append(context.getString(R.string.migrate_done_firmware))
                }
                result.backupDir?.let {
                    append("\n\n").append(context.getString(R.string.migrate_done_backup, it.path))
                }
            }
            MessageDialogFragment.newInstance(
                activity,
                titleId = R.string.migrate_from_emulator,
                descriptionString = summary
            )
        }.show(fragment.parentFragmentManager, ProgressDialogFragment.TAG)
    }

    private fun showMessage(message: String) {
        MessageDialogFragment.newInstance(
            fragment.requireActivity(),
            titleId = R.string.migrate_from_emulator,
            descriptionString = message
        ).show(fragment.parentFragmentManager, MessageDialogFragment.TAG)
    }
}
