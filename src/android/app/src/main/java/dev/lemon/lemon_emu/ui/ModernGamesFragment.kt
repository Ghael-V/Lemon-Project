// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.lemon.lemon_emu.features.settings.utils.SettingsFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import dev.lemon.lemon_emu.HomeNavigationDirections
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.features.settings.model.BooleanSetting
import dev.lemon.lemon_emu.features.settings.ui.SettingsSubscreen
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.model.GamesViewModel
import dev.lemon.lemon_emu.model.HomeViewModel
import dev.lemon.lemon_emu.ui.main.MainActivity
import dev.lemon.lemon_emu.ui.modern.LibraryActions
import dev.lemon.lemon_emu.ui.modern.ModernLibraryScreen
import dev.lemon.lemon_emu.ui.modern.PullToRefresh
import dev.lemon.lemon_emu.ui.modern.ScanProgressCard
import dev.lemon.lemon_emu.utils.GameHelper
import dev.lemon.lemon_emu.utils.GameIconUtils
import dev.lemon.lemon_emu.utils.GameLaunchUtils
import dev.lemon.lemon_emu.utils.GpuDriverHelper
import dev.lemon.lemon_emu.utils.NativeConfig
import dev.lemon.lemon_emu.utils.PerformancePresets
import dev.lemon.lemon_emu.utils.GameStatsUtils

/** The redesigned library (see [ModernLibraryScreen]); [GamesFragment] stays as the classic one. */
class ModernGamesFragment : Fragment() {
    private val gamesViewModel: GamesViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by activityViewModels()

    private val getGamesDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { result ->
            if (result != null) {
                (requireActivity() as MainActivity).processGamesDir(result, true)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val games by gamesViewModel.games.collectAsState()
            val reloading by gamesViewModel.isReloading.collectAsState()
            val preferences = remember {
                PreferenceManager.getDefaultSharedPreferences(requireContext().applicationContext)
            }
            val lastPlayedPath = remember(games) {
                GameStatsUtils.findLastPlayed(requireContext(), games)?.path
            }
            val actions = remember { buildActions() }

            val scan by GameHelper.scanProgress.collectAsState()
            Box(Modifier.fillMaxSize()) {
                PullToRefresh(
                    refreshing = reloading,
                    onRefresh = { gamesViewModel.reloadGames(false) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    ModernLibraryScreen(
                        games = games,
                        loading = reloading,
                        preferences = preferences,
                        initialSelectedPath = lastPlayedPath,
                        showQLaunch = BooleanSetting.ENABLE_QLAUNCH_BUTTON.getBoolean() &&
                            NativeLibrary.isFirmwareAvailable(),
                        actions = actions
                    )
                }
                // A library scan can take minutes with many games (or one big compressed one):
                // say so, in the middle while there is nothing to show yet, at the bottom otherwise.
                scan?.let {
                    ScanProgressCard(
                        it,
                        Modifier.align(if (games.isEmpty()) Alignment.Center else Alignment.BottomCenter)
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setStatusBarShadeVisibility(false)
    }

    private fun buildActions() = LibraryActions(
        onLaunch = { game ->
            GameLaunchUtils.launchGame(
                requireActivity() as AppCompatActivity,
                game,
                findNavController()
            )
        },
        onDetails = { game ->
            findNavController().navigate(
                HomeNavigationDirections.actionGlobalPerGamePropertiesFragment(game)
            )
        },
        onToggleFavorite = { game ->
            val preferences =
                PreferenceManager.getDefaultSharedPreferences(requireContext().applicationContext)
            preferences.edit {
                putBoolean(game.keyIsFavorite, !preferences.getBoolean(game.keyIsFavorite, false))
            }
        },
        onSettings = { findNavController().navigate(R.id.action_gamesFragment_to_homeSettingsFragment) },
        onStatistics = { findNavController().navigate(R.id.action_global_statisticsFragment) },
        onAddGames = { getGamesDirectory.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data) },
        onManageFolders = {
            findNavController().navigate(
                HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                    SettingsSubscreen.GAME_FOLDERS,
                    null
                )
            )
        },
        onInstallContent = {
            findNavController().navigate(
                HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                    SettingsSubscreen.INSTALLABLE,
                    null
                )
            )
        },
        onLaunchQLaunch = ::launchQLaunch,
        onPerformance = ::showPerformancePresetDialog,
        onDriverSettings = { game ->
            findNavController().navigate(
                HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                    SettingsSubscreen.FREEDRENO_SETTINGS,
                    game
                )
            )
        },
        onAddShortcut = ::requestPinShortcut,
        hasDriverOption = GpuDriverHelper.isAdrenoGpu(),
        canPinShortcut = ShortcutManagerCompat.isRequestPinShortcutSupported(requireContext())
    )

    private fun requestPinShortcut(game: Game) {
        val activity = requireActivity() as AppCompatActivity
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val shortcut = ShortcutInfoCompat.Builder(activity, "pin_${game.path}")
                    .setShortLabel(game.title)
                    .setIcon(GameIconUtils.getShortcutIcon(activity, game))
                    .setIntent(game.launchIntent)
                    .build()
                ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)
            }
        }
    }

    private fun showPerformancePresetDialog(game: Game) {
        val presets = PerformancePresets.Preset.entries.toTypedArray()
        val labels = presets.map { getString(it.titleRes) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.performance_preset)
            .setItems(labels) { dialog, which ->
                val preset = presets[which]
                SettingsFile.loadCustomConfig(game)
                PerformancePresets.apply(preset)
                NativeConfig.savePerGameConfig()
                NativeConfig.unloadPerGameConfig()
                dialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.preset_applied_per_game, getString(preset.titleRes)),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .show()
    }

    private fun launchQLaunch() {
        try {
            val qlaunchGame = GameLaunchUtils.qlaunchGame()
            if (qlaunchGame == null) {
                Toast.makeText(requireContext(), R.string.applets_error_applet, Toast.LENGTH_SHORT).show()
                return
            }
            findNavController().navigate(HomeNavigationDirections.actionGlobalEmulationActivity(qlaunchGame))
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to launch QLaunch: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
