// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.features.LibraryProvider
import dev.lemon.lemon_emu.features.settings.model.Settings
import dev.lemon.lemon_emu.features.settings.ui.SettingsActivity
import dev.lemon.lemon_emu.features.settings.ui.SettingsActivityArgs
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.ui.main.MainActivity
import dev.lemon.lemon_emu.utils.FileUtil
import dev.lemon.lemon_emu.utils.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a frontend (a launcher such as Bramble) may ask Lemon to do for one game of its library,
 * each through Lemon's own screen:
 * - `<package>.OPEN_GAME_SETTINGS`: opens the game's own settings.
 * - `<package>.IMPORT_SAVES` with a ZIP as data: replaces the game's save data, after the user
 *   confirms here.
 * - `<package>.EXPORT_SAVES` (for a result): after the user agrees here, answers with the URI of
 *   the save ZIP and a grant to read it. Nothing else can read it.
 * The game comes as text extras `doc_id` (its document id) or `program_id` (16 hex digits).
 */
class ExternalGameActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val action = intent.action.orEmpty()
        val game = LibraryProvider.gameFromExtras(
            this,
            intent.getStringExtra(EXTRA_DOC_ID),
            intent.getStringExtra(EXTRA_PROGRAM_ID)
        )
        when (action) {
            packageName + ACTION_OPEN_GAME_SETTINGS -> openSettings(game)
            packageName + ACTION_IMPORT_SAVES -> importSaves(game, intent.data)
            packageName + ACTION_EXPORT_SAVES -> exportSaves(game)
            else -> finish()
        }
    }

    private fun openSettings(game: Game?) {
        if (game == null) {
            Toast.makeText(this, R.string.external_game_not_found, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtras(SettingsActivityArgs(game, Settings.MenuTag.SECTION_ROOT).toBundle())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    private fun importSaves(requested: Game?, zip: Uri?) {
        if (zip == null) {
            finish()
            return
        }
        if (NativeLibrary.isRunning()) {
            finishWith(R.string.external_close_game_first)
            return
        }
        lifecycleScope.launch {
            // The ZIP holds the save folder named by the game's program id, as Lemon exports it.
            val staging = File(cacheDir, "external_saves")
            val unpacked = withContext(Dispatchers.IO) {
                runCatching {
                    staging.deleteRecursively()
                    staging.mkdirs()
                    FileUtil.unzipToInternalStorage(zip.toString(), staging)
                    staging.listFiles()?.filter { it.isDirectory }.orEmpty()
                }.onFailure { Log.error("[ExternalGame] Could not read the save ZIP: ${it.message}") }
                    .getOrNull()
            }
            val game = requested ?: unpacked?.firstNotNullOfOrNull {
                LibraryProvider.gameWithProgramId(this@ExternalGameActivity, it.name)
            }
            val folder = game?.let { found -> unpacked?.firstOrNull { it.name == found.programIdHex } }
            if (game == null || folder == null) {
                staging.deleteRecursively()
                finishWith(
                    if (game == null) R.string.external_game_not_found else R.string.external_zip_wrong_game,
                    game?.title
                )
                return@launch
            }
            MaterialAlertDialogBuilder(this@ExternalGameActivity)
                .setTitle(R.string.external_import_saves_title)
                .setMessage(getString(R.string.external_import_saves_message, game.title))
                .setPositiveButton(R.string.external_replace) { _, _ -> replaceSaves(game, folder, staging) }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    staging.deleteRecursively()
                    finish()
                }
                .setOnCancelListener {
                    staging.deleteRecursively()
                    finish()
                }
                .show()
        }
    }

    private fun replaceSaves(game: Game, folder: File, staging: File) {
        lifecycleScope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching {
                    val saves = File(game.saveDir)
                    saves.deleteRecursively()
                    saves.mkdirs()
                    folder.copyRecursively(saves, overwrite = true)
                }.onFailure { Log.error("[ExternalGame] Import failed: ${it.message}") }
                    .also { staging.deleteRecursively() }
                    .isSuccess
            }
            finishWith(if (done) R.string.save_file_imported_success else R.string.fatal_error)
        }
    }

    private fun exportSaves(game: Game?) {
        if (game == null) {
            finishWith(R.string.external_game_not_found, result = Activity.RESULT_CANCELED)
            return
        }
        val hasSaves = LibraryProvider.saveFolder(game)?.walkTopDown()?.any { it.isFile } == true
        val programId = game.programIdHex
        if (!hasSaves || programId.length != 16) {
            finishWith(R.string.external_no_saves, game.title, Activity.RESULT_CANCELED)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.external_export_saves_title)
            .setMessage(getString(R.string.external_export_saves_message, callerName(), game.title))
            .setPositiveButton(R.string.external_allow) { _, _ ->
                setResult(
                    Activity.RESULT_OK,
                    Intent().setData(LibraryProvider.saveZipUri(this, programId))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
            .setOnCancelListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
            .show()
    }

    /** The asking app's name, or a generic one when it did not ask for a result. */
    private fun callerName(): String {
        val caller = callingPackage ?: return getString(R.string.external_another_app)
        return runCatching {
            @Suppress("DEPRECATION")
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(caller, 0)).toString()
        }.getOrDefault(caller)
    }

    private fun finishWith(text: Int, game: String? = null, result: Int? = null) {
        val message = if (game != null) getString(text, game) else getString(text)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        result?.let { setResult(it) }
        finish()
    }

    companion object {
        const val EXTRA_DOC_ID = "doc_id"
        const val EXTRA_PROGRAM_ID = "program_id"
        private const val ACTION_OPEN_GAME_SETTINGS = ".OPEN_GAME_SETTINGS"
        private const val ACTION_IMPORT_SAVES = ".IMPORT_SAVES"
        private const val ACTION_EXPORT_SAVES = ".EXPORT_SAVES"
    }
}
