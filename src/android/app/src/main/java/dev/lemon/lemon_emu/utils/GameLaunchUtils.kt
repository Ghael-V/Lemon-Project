// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.utils

import android.content.DialogInterface
import android.text.Html
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.lemon.lemon_emu.HomeNavigationDirections
import dev.lemon.lemon_emu.LemonApplication
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.model.GamesViewModel
import dev.lemon.lemon_emu.widget.GameLauncherWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared "tap a game to play it" flow: verifies the ROM file still exists, warns about missing
 * firmware if needed, then records last-played time, updates the shortcut/widget and navigates
 * to [dev.lemon.lemon_emu.activities.EmulationActivity]. Used both by the games list cards and
 * the "continue playing" quick-launch card.
 */
object GameLaunchUtils {
    fun launchGame(activity: AppCompatActivity, game: Game, navController: NavController) {
        val gameExists = DocumentFile.fromSingleUri(
            LemonApplication.appContext,
            game.path.toUri()
        )?.exists() == true

        if (!gameExists) {
            Toast.makeText(
                LemonApplication.appContext,
                R.string.loader_error_file_not_found,
                Toast.LENGTH_LONG
            ).show()

            ViewModelProvider(activity)[GamesViewModel::class.java].reloadGames(true)
            return
        }

        val launch: () -> Unit = {
            val preferences = PreferenceManager.getDefaultSharedPreferences(LemonApplication.appContext)
            preferences.edit {
                putLong(game.keyLastPlayedTime, System.currentTimeMillis())
            }

            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val shortcut = ShortcutInfoCompat.Builder(LemonApplication.appContext, game.path)
                        .setShortLabel(game.title)
                        .setIcon(GameIconUtils.getShortcutIcon(activity, game))
                        .setIntent(game.launchIntent)
                        .build()
                    ShortcutManagerCompat.pushDynamicShortcut(LemonApplication.appContext, shortcut)
                    GameLauncherWidgetProvider.setLastPlayedGame(
                        LemonApplication.appContext,
                        game.path,
                        game.title
                    )
                }
            }

            val action = HomeNavigationDirections.actionGlobalEmulationActivity(game, true)
            navController.navigate(action)
        }

        if (NativeLibrary.gameRequiresFirmware(game.programId) && !NativeLibrary.isFirmwareAvailable()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.loader_requires_firmware)
                .setMessage(
                    Html.fromHtml(
                        activity.getString(R.string.loader_requires_firmware_description),
                        Html.FROM_HTML_MODE_LEGACY
                    )
                )
                .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int -> launch() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
        } else {
            launch()
        }
    }
}
