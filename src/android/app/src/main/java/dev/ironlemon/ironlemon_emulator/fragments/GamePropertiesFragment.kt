// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.text.format.DateUtils
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.navigation.fragment.findNavController
import dev.ironlemon.ironlemon_emulator.ui.modern.GameDetailActions
import dev.ironlemon.ironlemon_emulator.ui.modern.ModernGameDetail
import dev.ironlemon.ironlemon_emulator.utils.GameStatsUtils
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ironlemon.ironlemon_emulator.HomeNavigationDirections
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.YuzuApplication
import dev.ironlemon.ironlemon_emulator.adapters.GamePropertiesAdapter
import dev.ironlemon.ironlemon_emulator.features.settings.model.Settings
import dev.ironlemon.ironlemon_emulator.model.DriverViewModel
import dev.ironlemon.ironlemon_emulator.model.GameProperty
import dev.ironlemon.ironlemon_emulator.model.GamesViewModel
import dev.ironlemon.ironlemon_emulator.model.HomeViewModel
import dev.ironlemon.ironlemon_emulator.model.InstallableProperty
import dev.ironlemon.ironlemon_emulator.model.SubmenuProperty
import dev.ironlemon.ironlemon_emulator.model.TaskState
import dev.ironlemon.ironlemon_emulator.utils.DirectoryInitialization
import dev.ironlemon.ironlemon_emulator.utils.FileUtil
import dev.ironlemon.ironlemon_emulator.utils.GameIconUtils
import dev.ironlemon.ironlemon_emulator.utils.GpuDriverHelper
import dev.ironlemon.ironlemon_emulator.utils.MemoryUtil
import dev.ironlemon.ironlemon_emulator.utils.Log
import dev.ironlemon.ironlemon_emulator.utils.ViewUtils.marquee
import dev.ironlemon.ironlemon_emulator.utils.ViewUtils.updateMargins
import dev.ironlemon.ironlemon_emulator.utils.collect
import java.io.BufferedOutputStream
import java.io.File

class GamePropertiesFragment : Fragment() {

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val gamesViewModel: GamesViewModel by activityViewModels()
    private val driverViewModel: DriverViewModel by activityViewModels()

    private val args by navArgs<GamePropertiesFragmentArgs>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Y, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    private val propertiesState = mutableStateOf<List<GameProperty>>(emptyList())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val game = args.game
        val shortcutManager = requireActivity().getSystemService(ShortcutManager::class.java)
        val actions = GameDetailActions(
            onBack = { findNavController().popBackStack() },
            onPlay = {
                LaunchGameDialogFragment.newInstance(game)
                    .show(childFragmentManager, LaunchGameDialogFragment.TAG)
            },
            onShortcut = ::pinShortcut,
            onEditPlaytime = {}
        )
        setContent {
            val context = LocalContext.current
            val sessions = GameStatsUtils.getSessionCount(context, game)
            val lastPlayed = GameStatsUtils.getLastPlayedMillis(context, game)
            ModernGameDetail(
                game = game,
                playtime = GameStatsUtils.buildAbbreviated(context, game)
                    ?.let { context.getString(R.string.ironlemon_playtime, it) }
                    ?: context.getString(R.string.ironlemon_never_played),
                usage = if (lastPlayed > 0) {
                    context.getString(
                        R.string.ironlemon_usage,
                        context.resources.getQuantityString(
                            R.plurals.ironlemon_sessions,
                            sessions,
                            sessions
                        ),
                        // Inside a sentence: "last played 3 hours ago", not "...Hace 3 horas".
                        DateUtils.getRelativeTimeSpanString(
                            lastPlayed,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString().replaceFirstChar { it.lowercase() }
                    )
                } else {
                    ""
                },
                properties = propertiesState.value,
                playEnabled = true,
                canPinShortcut = shortcutManager.isRequestPinShortcutSupported,
                actions = actions
            )
        }
    }

    private fun pinShortcut() {
        val shortcutManager = requireActivity().getSystemService(ShortcutManager::class.java)
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val shortcut = ShortcutInfo.Builder(requireContext(), args.game.title)
                    .setShortLabel(args.game.title)
                    .setIcon(
                        GameIconUtils.getShortcutIcon(requireActivity(), args.game)
                            .toIcon(requireContext())
                    )
                    .setIntent(args.game.launchIntent)
                    .build()
                shortcutManager.requestPinShortcut(shortcut, null)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setNavigationVisibility(visible = false, animated = true)
        homeViewModel.setStatusBarShadeVisibility(false)

        reloadList()

        homeViewModel.openImportSaves.collect(
            viewLifecycleOwner,
            resetState = { homeViewModel.setOpenImportSaves(false) }
        ) { if (it) importSaves.launch(arrayOf("application/zip")) }
        homeViewModel.reloadPropertiesList.collect(
            viewLifecycleOwner,
            resetState = { homeViewModel.reloadPropertiesList(false) }
        ) { if (it) reloadList() }
    }

    override fun onDestroy() {
        super.onDestroy()
        gamesViewModel.reloadGames(true)
    }

    private fun reloadList() {
        view ?: return

        driverViewModel.updateDriverNameForGame(args.game)
        val properties = mutableListOf<GameProperty>().apply {
            add(
                SubmenuProperty(
                    R.string.info,
                    R.string.info_description,
                    R.drawable.ic_info_outline
                ) {
                    val action = GamePropertiesFragmentDirections
                        .actionPerGamePropertiesFragmentToGameInfoFragment(args.game)
                    findNavController().navigate(action)
                }
            )
            add(
                SubmenuProperty(
                    R.string.preferences_settings,
                    R.string.per_game_settings_description,
                    R.drawable.ic_settings
                ) {
                    val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                        args.game,
                        Settings.MenuTag.SECTION_ROOT
                    )
                    findNavController().navigate(action)
                }
            )

            if (GpuDriverHelper.supportsCustomDriverLoading()) {
                add(
                    SubmenuProperty(
                        R.string.gpu_driver_manager,
                        R.string.install_gpu_driver_description,
                        R.drawable.ic_build,
                        detailsFlow = driverViewModel.selectedDriverTitle
                    ) {
                        val action = GamePropertiesFragmentDirections
                            .actionPerGamePropertiesFragmentToDriverManagerFragment(args.game)
                        findNavController().navigate(action)
                    }
                )
            }

            if (!args.game.isHomebrew) {
                add(
                    SubmenuProperty(
                        R.string.add_ons,
                        R.string.add_ons_description,
                        R.drawable.ic_edit
                    ) {
                        val action = GamePropertiesFragmentDirections
                            .actionPerGamePropertiesFragmentToAddonsFragment(args.game)
                        findNavController().navigate(action)
                    }
                )
                add(
                    InstallableProperty(
                        R.string.save_data,
                        R.string.save_data_description,
                        R.drawable.ic_save,
                        {
                            MessageDialogFragment.newInstance(
                                requireActivity(),
                                titleId = R.string.import_save_warning,
                                descriptionId = R.string.import_save_warning_description,
                                positiveAction = { homeViewModel.setOpenImportSaves(true) }
                            ).show(parentFragmentManager, MessageDialogFragment.TAG)
                        },
                        if (File(args.game.saveDir).exists()) {
                            { exportSaves.launch(args.game.saveZipName) }
                        } else {
                            null
                        }
                    )
                )
                add(
                    SubmenuProperty(
                        R.string.cheats,
                        R.string.cheats_description,
                        R.drawable.ic_key,
                        action = {
                            CheatsDialogFragment.newInstance(args.game)
                                .show(childFragmentManager, CheatsDialogFragment.TAG)
                        }
                    )
                )

                val saveDirFile = File(args.game.saveDir)
                if (saveDirFile.exists()) {
                    add(
                        SubmenuProperty(
                            R.string.delete_save_data,
                            R.string.delete_save_data_description,
                            R.drawable.ic_delete,
                            action = {
                                MessageDialogFragment.newInstance(
                                    requireActivity(),
                                    titleId = R.string.delete_save_data,
                                    descriptionId = R.string.delete_save_data_warning_description,
                                    positiveButtonTitleId = android.R.string.cancel,
                                    negativeButtonTitleId = android.R.string.ok,
                                    negativeAction = {
                                        File(args.game.saveDir).deleteRecursively()
                                        Toast.makeText(
                                            YuzuApplication.appContext,
                                            R.string.save_data_deleted_successfully,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        homeViewModel.reloadPropertiesList(true)
                                    }
                                ).show(parentFragmentManager, MessageDialogFragment.TAG)
                            }
                        )
                    )
                }

                val shaderCacheDir = File(
                    DirectoryInitialization.userDirectory +
                        "/shader/" + args.game.settingsName.lowercase()
                )
                if (shaderCacheDir.exists()) {
                    add(
                        SubmenuProperty(
                            R.string.clear_shader_cache,
                            R.string.clear_shader_cache_description,
                            R.drawable.ic_delete,
                            {
                                if (shaderCacheDir.exists()) {
                                    val bytes = shaderCacheDir.walkTopDown().filter { it.isFile }
                                        .map { it.length() }.sum()
                                    MemoryUtil.bytesToSizeUnit(bytes.toFloat())
                                } else {
                                    MemoryUtil.bytesToSizeUnit(0f)
                                }
                            }
                        ) {
                            MessageDialogFragment.newInstance(
                                requireActivity(),
                                titleId = R.string.clear_shader_cache,
                                descriptionId = R.string.clear_shader_cache_warning_description,
                                positiveAction = {
                                    shaderCacheDir.deleteRecursively()
                                    Toast.makeText(
                                        YuzuApplication.appContext,
                                        R.string.cleared_shaders_successfully,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    homeViewModel.reloadPropertiesList(true)
                                }
                            ).show(parentFragmentManager, MessageDialogFragment.TAG)
                        }
                    )
                }
            }
        }
        propertiesState.value = properties
    }

    override fun onResume() {
        super.onResume()
        driverViewModel.updateDriverNameForGame(args.game)
    }


    private val importSaves =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            if (result == null) {
                return@registerForActivityResult
            }

            val savesFolder = File(args.game.saveDir)
            val cacheSaveDir = File("${requireContext().cacheDir.path}/saves/")
            cacheSaveDir.mkdir()

            ProgressDialogFragment.newInstance(
                requireActivity(),
                R.string.save_files_importing,
                false
            ) { _, _ ->
                try {
                    FileUtil.unzipToInternalStorage(result.toString(), cacheSaveDir)
                    val files = cacheSaveDir.listFiles()
                    var savesFolderFile: File? = null
                    if (files != null) {
                        val savesFolderName = args.game.programIdHex
                        for (file in files) {
                            if (file.isDirectory && file.name == savesFolderName) {
                                savesFolderFile = file
                                break
                            }
                        }
                    }

                    if (savesFolderFile != null) {
                        savesFolder.deleteRecursively()
                        savesFolder.mkdir()
                        savesFolderFile.copyRecursively(savesFolder)
                        savesFolderFile.deleteRecursively()
                    }

                    withContext(Dispatchers.Main) {
                        if (savesFolderFile == null) {
                            MessageDialogFragment.newInstance(
                                requireActivity(),
                                titleId = R.string.save_file_invalid_zip_structure,
                                descriptionId = R.string.save_file_invalid_zip_structure_description
                            ).show(parentFragmentManager, MessageDialogFragment.TAG)
                            return@withContext
                        }
                        Toast.makeText(
                            YuzuApplication.appContext,
                            getString(R.string.save_file_imported_success),
                            Toast.LENGTH_LONG
                        ).show()
                        homeViewModel.reloadPropertiesList(true)
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

    /**
     * Exports the save file located in the given folder path by creating a zip file and opening a
     * file picker to save.
     */
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
            val saveLocation = args.game.saveDir
            val zipResult = FileUtil.zipFromInternalStorage(
                File(saveLocation),
                saveLocation.replaceAfterLast("/", ""),
                BufferedOutputStream(requireContext().contentResolver.openOutputStream(result)),
                compression = false
            )
            return@newInstance when (zipResult) {
                TaskState.Completed -> getString(R.string.export_success)
                TaskState.Cancelled, TaskState.Failed -> getString(R.string.export_failed)
            }
        }.show(parentFragmentManager, ProgressDialogFragment.TAG)
    }
}
