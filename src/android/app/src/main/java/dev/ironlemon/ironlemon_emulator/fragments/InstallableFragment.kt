// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.YuzuApplication
import dev.ironlemon.ironlemon_emulator.model.Installable
import dev.ironlemon.ironlemon_emulator.model.TaskState
import dev.ironlemon.ironlemon_emulator.ui.main.MainActivity
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonActionCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonCardGrid
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonPage
import dev.ironlemon.ironlemon_emulator.utils.DirectoryInitialization
import dev.ironlemon.ironlemon_emulator.utils.FileUtil
import dev.ironlemon.ironlemon_emulator.utils.collect
import java.io.BufferedOutputStream
import java.io.File
import java.math.BigInteger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class InstallableFragment : IronLemonComposeFragment() {
    private var installables by mutableStateOf<List<Installable>>(emptyList())

    @Composable
    override fun ScreenContent() {
        IronLemonPage(title = stringResource(R.string.manage_yuzu_data), onBack = ::goBack) {
            IronLemonCardGrid {
                items(installables) { item ->
                    IronLemonActionCard(
                        title = stringResource(item.titleId),
                        description = stringResource(item.descriptionId)
                    ) {
                        item.export?.let {
                            IronLemonButton(
                                text = stringResource(R.string.export),
                                iconRes = R.drawable.ic_export,
                                height = 44.dp,
                                onClick = it
                            )
                        }
                        item.install?.let {
                            IronLemonButton(
                                text = stringResource(R.string.string_import),
                                iconRes = R.drawable.ic_import,
                                primary = true,
                                height = 44.dp,
                                onClick = it
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = requireActivity() as MainActivity

        homeViewModel.openImportSaves.collect(viewLifecycleOwner) {
            if (it) {
                importSaves.launch(arrayOf("application/zip"))
                homeViewModel.setOpenImportSaves(false)
            }
        }

        installables = listOf(
            Installable(
                R.string.migrate_from_emulator,
                R.string.migrate_from_emulator_description,
                install = { emulatorMigration.start() }
            ),
            Installable(
                R.string.user_data,
                R.string.user_data_description,
                install = { mainActivity.importUserData.launch(arrayOf("application/zip")) },
                export = { mainActivity.exportUserData.launch("export.zip") }
            ),
            Installable(
                R.string.manage_save_data,
                R.string.manage_save_data_description,
                install = {
                    MessageDialogFragment.newInstance(
                        requireActivity(),
                        titleId = R.string.import_save_warning,
                        descriptionId = R.string.import_save_warning_description,
                        positiveAction = { homeViewModel.setOpenImportSaves(true) }
                    ).show(parentFragmentManager, MessageDialogFragment.TAG)
                },
                export = {
                    val oldSaveDataFolder = File(
                        "${DirectoryInitialization.userDirectory}/nand" +
                            NativeLibrary.getDefaultProfileSaveDataRoot(false)
                    )
                    val futureSaveDataFolder = File(
                        "${DirectoryInitialization.userDirectory}/nand" +
                            NativeLibrary.getDefaultProfileSaveDataRoot(true)
                    )
                    if (!oldSaveDataFolder.exists() && !futureSaveDataFolder.exists()) {
                        Toast.makeText(
                            YuzuApplication.appContext,
                            R.string.no_save_data_found,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@Installable
                    } else {
                        exportSaves.launch(
                            "${getString(R.string.save_data)} " +
                                LocalDateTime.now().format(
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                )
                        )
                    }
                }
            ),
            Installable(
                R.string.install_game_content,
                R.string.install_game_content_description,
                install = { mainActivity.installGameUpdate.launch(arrayOf("*/*")) }
            ),
            Installable(
                R.string.install_firmware,
                R.string.install_firmware_description,
                install = { mainActivity.getFirmware.launch(arrayOf("application/zip")) }
            ),
            Installable(
                R.string.install_prod_keys,
                R.string.install_prod_keys_description,
                install = { mainActivity.getProdKey.launch(arrayOf("*/*")) }
            ),
            Installable(
                R.string.install_amiibo_keys,
                R.string.install_amiibo_keys_description,
                install = { mainActivity.getAmiiboKey.launch(arrayOf("*/*")) }
            )
        )

    }

    private val importSaves =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            if (result == null) {
                return@registerForActivityResult
            }

            val cacheSaveDir = File("${requireContext().cacheDir.path}/saves/")
            cacheSaveDir.mkdir()

            ProgressDialogFragment.newInstance(
                requireActivity(),
                R.string.save_files_importing,
                false
            ) { progressCallback, _ ->
                try {
                    FileUtil.unzipToInternalStorage(
                        result.toString(),
                        cacheSaveDir,
                        progressCallback
                    )
                    val files = cacheSaveDir.listFiles()
                    var successfulImports = 0
                    var failedImports = 0
                    if (files != null) {
                        for (file in files) {
                            if (file.isDirectory) {
                                // Folders are named after the game's title id; anything else in
                                // the zip is counted as a failed import instead of aborting all.
                                val titleId = file.name.toBigIntegerOrNull(16)
                                val baseSaveDir =
                                    titleId?.let { NativeLibrary.getSavePath(it.toString()) } ?: ""
                                if (baseSaveDir.isEmpty()) {
                                    failedImports++
                                    continue
                                }

                                val internalSaveFolder = File(
                                    "${DirectoryInitialization.userDirectory}/nand$baseSaveDir"
                                )
                                internalSaveFolder.deleteRecursively()
                                internalSaveFolder.mkdir()
                                file.copyRecursively(target = internalSaveFolder, overwrite = true)
                                successfulImports++
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (successfulImports == 0) {
                            MessageDialogFragment.newInstance(
                                requireActivity(),
                                titleId = R.string.save_file_invalid_zip_structure,
                                descriptionId = R.string.save_file_invalid_zip_structure_description
                            ).show(parentFragmentManager, MessageDialogFragment.TAG)
                            return@withContext
                        }
                        val successString = if (failedImports > 0) {
                            """
                            ${
                            requireContext().resources.getQuantityString(
                                R.plurals.saves_import_success,
                                successfulImports,
                                successfulImports
                            )
                            }
                            ${
                            requireContext().resources.getQuantityString(
                                R.plurals.saves_import_failed,
                                failedImports,
                                failedImports
                            )
                            }
                            """
                        } else {
                            requireContext().resources.getQuantityString(
                                R.plurals.saves_import_success,
                                successfulImports,
                                successfulImports
                            )
                        }
                        MessageDialogFragment.newInstance(
                            requireActivity(),
                            titleId = R.string.import_complete,
                            descriptionString = successString
                        ).show(parentFragmentManager, MessageDialogFragment.TAG)
                    }

                    cacheSaveDir.deleteRecursively()
                } catch (e: Exception) {
                    Log.error("[SaveImport] Import failed: ${e.message}")
                    cacheSaveDir.deleteRecursively()
                    // This runs on a worker thread: a Toast from here threw and crashed the app.
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            YuzuApplication.appContext,
                            YuzuApplication.appContext.getString(R.string.fatal_error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.show(parentFragmentManager, ProgressDialogFragment.TAG)
        }

    private val emulatorMigration = EmulatorMigrationFlow(this)

    private val exportSaves = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { result ->
        if (result == null) {
            return@registerForActivityResult
        }

        ProgressDialogFragment.newInstance(
            requireActivity(),
            R.string.save_files_exporting,
            false
        ) { _, _ ->
            val cacheSaveDir = File("${requireContext().cacheDir.path}/saves/")
            cacheSaveDir.mkdir()

            val oldSaveDataFolder = File(
                "${DirectoryInitialization.userDirectory}/nand" +
                    NativeLibrary.getDefaultProfileSaveDataRoot(false)
            )
            if (oldSaveDataFolder.exists()) {
                oldSaveDataFolder.copyRecursively(cacheSaveDir)
            }

            val futureSaveDataFolder = File(
                "${DirectoryInitialization.userDirectory}/nand" +
                    NativeLibrary.getDefaultProfileSaveDataRoot(true)
            )
            if (futureSaveDataFolder.exists()) {
                futureSaveDataFolder.copyRecursively(cacheSaveDir)
            }

            val saveFilesTotal = cacheSaveDir.listFiles()?.size ?: 0
            if (saveFilesTotal == 0) {
                cacheSaveDir.deleteRecursively()
                return@newInstance getString(R.string.no_save_data_found)
            }

            val zipResult = FileUtil.zipFromInternalStorage(
                cacheSaveDir,
                cacheSaveDir.path,
                BufferedOutputStream(requireContext().contentResolver.openOutputStream(result))
            )
            cacheSaveDir.deleteRecursively()

            return@newInstance when (zipResult) {
                TaskState.Completed -> getString(R.string.export_success)
                TaskState.Cancelled, TaskState.Failed -> getString(R.string.export_failed)
            }
        }.show(parentFragmentManager, ProgressDialogFragment.TAG)
    }
}
