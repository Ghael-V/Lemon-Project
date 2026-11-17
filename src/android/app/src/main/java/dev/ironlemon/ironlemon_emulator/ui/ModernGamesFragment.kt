// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project / Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.ui

import dev.ironlemon.ironlemon_emulator.ui.modern.showPerformancePresetDialog
import dev.ironlemon.ironlemon_emulator.utils.PerformancePresets
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDirections
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import dev.ironlemon.ironlemon_emulator.HomeNavigationDirections
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.model.GamesViewModel
import dev.ironlemon.ironlemon_emulator.model.HomeViewModel
import dev.ironlemon.ironlemon_emulator.ui.main.MainActivity
import dev.ironlemon.ironlemon_emulator.ui.modern.LibraryActions
import dev.ironlemon.ironlemon_emulator.ui.modern.ModernLibraryScreen
import dev.ironlemon.ironlemon_emulator.ui.modern.PullToRefresh
import dev.ironlemon.ironlemon_emulator.ui.modern.ScanProgressCard
import dev.ironlemon.ironlemon_emulator.utils.GameHelper
import dev.ironlemon.ironlemon_emulator.ui.modern.PullToRefresh
import dev.ironlemon.ironlemon_emulator.utils.GameIconUtils
import dev.ironlemon.ironlemon_emulator.utils.GameLaunchUtils
import dev.ironlemon.ironlemon_emulator.utils.GameStatsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Iron Lemon library (see [ModernLibraryScreen]), hosted where yuzu's game list was. It keeps
 * the gamesFragment destination, so every navigation action from it still works.
 */
class ModernGamesFragment : Fragment() {
    private val gamesViewModel: GamesViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by activityViewModels()

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
                        showQLaunch = NativeLibrary.isFirmwareAvailable(),
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
        // The library has its own settings and folder buttons: no bottom navigation here.
        homeViewModel.setNavigationVisibility(visible = false, animated = false)
        homeViewModel.setStatusBarShadeVisibility(false)
    }

    /**
     * Navigates only while the library is the current screen: a second tap during the first
     * navigation's transition would navigate again from a screen that is already leaving, which
     * leaves the navigation library without the fragment and closes the app on the next pause.
     */
    private fun navigateFromLibrary(action: Int) {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.gamesFragment) {
            navController.navigate(action)
        }
    }

    private fun navigateFromLibrary(directions: NavDirections) {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.gamesFragment) {
            navController.navigate(directions)
        }
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
            navigateFromLibrary(
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
        onSettings = { navigateFromLibrary(R.id.homeSettingsFragment) },
        onStatistics = { navigateFromLibrary(R.id.statisticsFragment) },
        onAddGames = {
            (requireActivity() as MainActivity).getGamesDirectory
                .launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
        },
        onManageFolders = { navigateFromLibrary(R.id.gameFoldersFragment) },
        onInstallContent = { navigateFromLibrary(R.id.installableFragment) },
        onLaunchQLaunch = {
            val qlaunchGame = GameLaunchUtils.qlaunchGame()
            if (qlaunchGame == null) {
                Toast.makeText(requireContext(), R.string.qlaunch_not_found, Toast.LENGTH_SHORT)
                    .show()
            } else {
                navigateFromLibrary(
                    HomeNavigationDirections.actionGlobalEmulationActivity(qlaunchGame)
                )
            }
        },
        onPerformance = { game ->
            showPerformancePresetDialog(
                requireContext(),
                title = getString(R.string.performance_preset_for, game.title),
                current = PerformancePresets.currentFor(game),
                globalOption = true
            ) { preset -> PerformancePresets.applyFor(game, preset) }
        },
        onDriverSettings = {},
        onAddShortcut = ::requestPinShortcut,
        hasDriverOption = false,
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
}
