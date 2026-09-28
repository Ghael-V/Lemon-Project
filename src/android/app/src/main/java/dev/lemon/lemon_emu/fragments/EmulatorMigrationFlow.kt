// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.fragments

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.model.GamesViewModel
import dev.lemon.lemon_emu.utils.EmulatorMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "migrate from another emulator" dialogs, shared by every screen that offers it (setup
 * wizard, settings home, Install). Registers an activity result, so create it while the fragment
 * is being initialized, as a property.
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
            0 -> MessageDialogFragment.newInstance(
                fragment.requireActivity(),
                titleId = R.string.migrate_from_emulator,
                descriptionId = R.string.migrate_no_sources
            ).show(fragment.parentFragmentManager, MessageDialogFragment.TAG)

            1 -> pickFolder(sources.first())

            else -> MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.migrate_choose_source)
                .setItems(sources.map { it.label }.toTypedArray()) { _, which ->
                    pickFolder(sources[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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
            val dialog = MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(fragment.getString(R.string.migrate_confirm_title, source.label))
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
            if (plan.conflicts > 0) {
                dialog.setPositiveButton(R.string.migrate_replace) { _, _ -> run(plan, true) }
                dialog.setNeutralButton(R.string.migrate_keep) { _, _ -> run(plan, false) }
            } else {
                dialog.setPositiveButton(R.string.migrate_import) { _, _ -> run(plan, false) }
            }
            dialog.show()
        }
    }

    private fun run(plan: EmulatorMigration.Plan, replaceExisting: Boolean) {
        val activity = fragment.requireActivity()
        val context = activity.applicationContext
        val gamesViewModel = ViewModelProvider(activity)[GamesViewModel::class.java]
        ProgressDialogFragment.newInstance(
            activity,
            R.string.migrating,
            false
        ) { progressCallback, _ ->
            val result = EmulatorMigration.migrate(context, plan, replaceExisting) { done, total ->
                progressCallback(total, done)
            }
            if (result.keysImported || result.firmwareImported) {
                withContext(Dispatchers.Main) { gamesViewModel.reloadGames(true) }
            }
            buildString {
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
        }.apply {
            onDialogComplete = onFinished
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
