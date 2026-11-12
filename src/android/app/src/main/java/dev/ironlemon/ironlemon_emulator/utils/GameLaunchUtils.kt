// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import dev.ironlemon.ironlemon_emulator.HomeNavigationDirections
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.YuzuApplication
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.model.GamesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Starting a game from the library, as yuzu's game list does it. */
object GameLaunchUtils {
    fun launchGame(activity: AppCompatActivity, game: Game, navController: NavController) {
        val context = YuzuApplication.appContext
        val gameExists = DocumentFile.fromSingleUri(context, Uri.parse(game.path))?.exists() == true
        if (!gameExists) {
            Toast.makeText(context, R.string.loader_error_file_not_found, Toast.LENGTH_LONG).show()
            ViewModelProvider(activity)[GamesViewModel::class.java].reloadGames(true)
            return
        }

        GameStatsUtils.recordLaunch(context, game)

        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val shortcut = ShortcutInfoCompat.Builder(context, game.path)
                    .setShortLabel(game.title)
                    .setIcon(GameIconUtils.getShortcutIcon(activity, game))
                    .setIntent(game.launchIntent)
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            }
        }

        navController.navigate(HomeNavigationDirections.actionGlobalEmulationActivity(game, true))
    }
}
