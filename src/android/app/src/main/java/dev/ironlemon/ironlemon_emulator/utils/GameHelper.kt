// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.YuzuApplication
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.model.GameDir
import dev.ironlemon.ironlemon_emulator.model.MinimalDocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How far a library scan has got. [total] is 0 while the folders are still being listed.
 * [current] is the file being read right now.
 */
data class ScanProgress(val done: Int, val total: Int, val current: String)

object GameHelper {
    private const val KEY_OLD_GAME_PATH = "game_path"
    const val KEY_GAMES = "Games"

    private lateinit var preferences: SharedPreferences

    /** Non-null while a scan is running, so the library can show that work is going on. */
    val scanProgress: StateFlow<ScanProgress?> get() = _scanProgress
    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)

    // A scan clears and refills the native content provider, so two running at once (a library
    // reload plus CustomSettingsHandler's lookup) would each wipe what the other registered.
    @Synchronized
    fun getGames(): List<Game> {
        _scanProgress.value = ScanProgress(0, 0, "")
        try {
            return scanGames()
        } finally {
            _scanProgress.value = null
        }
    }

    /**
     * The library from its saved list, without reading every game again: only the folders are
     * listed. Files already in [cached] keep their entry and are just registered with the core
     * (updates and DLC in the folders come from there); new files are read; files that are gone
     * are dropped. A full scan stays for a refresh by hand or a change of folders.
     */
    @Synchronized
    fun refreshFromCache(cached: List<Game>): List<Game> {
        val context = YuzuApplication.appContext
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val gameFiles = listGameFiles() ?: return getGames()

        NativeLibrary.reloadKeys()
        NativeLibrary.clearFilesystemProvider()

        val byPath = cached.associateBy { it.path }
        val newFiles = gameFiles.filter { it.uri.toString() !in byPath }
        val games = mutableListOf<Game>()
        gameFiles.forEach { file ->
            byPath[file.uri.toString()]?.let {
                NativeLibrary.addFileToFilesystemProvider(it.path)
                games.add(it)
            }
        }
        if (newFiles.isNotEmpty()) {
            _scanProgress.value = ScanProgress(0, newFiles.size, "")
            try {
                newFiles.forEachIndexed { done, file ->
                    _scanProgress.value = ScanProgress(done, newFiles.size, file.filename)
                    getGame(file.uri, true)?.let { games.add(it) }
                }
            } finally {
                _scanProgress.value = null
            }
        }
        if (newFiles.isNotEmpty() || games.size != cached.size) {
            saveGames(games)
        }
        Log.info(
            "[GameHelper] Library from cache: ${games.size} games, ${newFiles.size} new, " +
                "${cached.size - (games.size - newFiles.size)} gone"
        )
        return games
    }

    /** Every game file in the library folders, or null if a folder can no longer be read. */
    private fun listGameFiles(): List<MinimalDocumentFile>? {
        val pending = mutableListOf<MinimalDocumentFile>()
        for (gameDir in NativeConfig.getGameDirs()) {
            val gameDirUri = Uri.parse(gameDir.uriString)
            if (!FileUtil.isTreeUriValid(gameDirUri)) {
                return null // The full scan drops the folder from the settings.
            }
            collectFiles(FileUtil.listFiles(gameDirUri), if (gameDir.deepScan) 3 else 1, pending)
        }
        return pending.filter { Game.extensions.contains(FileUtil.getExtension(it.uri)) }
    }

    private fun saveGames(games: List<Game>) {
        preferences.edit()
            .remove(KEY_GAMES)
            .putStringSet(KEY_GAMES, games.map { Json.encodeToString(it) }.toSet())
            .apply()
    }

    private fun scanGames(): List<Game> {
        val games = mutableListOf<Game>()
        val context = YuzuApplication.appContext
        preferences = PreferenceManager.getDefaultSharedPreferences(context)

        val gameDirs = mutableListOf<GameDir>()
        val oldGamesDir = preferences.getString(KEY_OLD_GAME_PATH, "") ?: ""
        if (oldGamesDir.isNotEmpty()) {
            gameDirs.add(GameDir(oldGamesDir, true))
            preferences.edit().remove(KEY_OLD_GAME_PATH).apply()
        }
        gameDirs.addAll(NativeConfig.getGameDirs())

        // Ensure keys are loaded so that ROM metadata can be decrypted.
        NativeLibrary.reloadKeys()

        // Reset metadata so we don't use stale information
        GameMetadata.resetMetadata()

        // Remove previous filesystem provider information so we can get up to date version info
        NativeLibrary.clearFilesystemProvider()

        val badDirs = mutableListOf<Int>()
        // List every folder first (quick), so the scan below knows how many files it has to read
        // and can report real progress. Files keep the order the old recursive walk visited them.
        val pending = mutableListOf<MinimalDocumentFile>()
        gameDirs.forEachIndexed { index: Int, gameDir: GameDir ->
            val gameDirUri = Uri.parse(gameDir.uriString)
            val isValid = FileUtil.isTreeUriValid(gameDirUri)
            if (isValid) {
                collectFiles(
                    FileUtil.listFiles(gameDirUri),
                    if (gameDir.deepScan) 3 else 1,
                    pending
                )
            } else {
                badDirs.add(index)
            }
        }
        val gameFiles = pending.filter { Game.extensions.contains(FileUtil.getExtension(it.uri)) }
        gameFiles.forEachIndexed { done, file ->
            _scanProgress.value = ScanProgress(done, gameFiles.size, file.filename)
            getGame(file.uri, true)?.let { games.add(it) }
        }

        // Remove all game dirs with insufficient permissions from config
        if (badDirs.isNotEmpty()) {
            var offset = 0
            badDirs.forEach {
                gameDirs.removeAt(it - offset)
                offset++
            }
        }
        NativeConfig.setGameDirs(gameDirs.toTypedArray())

        // Cache list of games found on disk
        saveGames(games)

        return games.toList()
    }

    // Flattens the folders (depth first, in listing order) into one list of files.
    private fun collectFiles(
        files: Array<MinimalDocumentFile>,
        depth: Int,
        out: MutableList<MinimalDocumentFile>
    ) {
        if (depth <= 0) {
            return
        }
        files.forEach {
            if (it.isDirectory) {
                collectFiles(FileUtil.listFiles(it.uri), depth - 1, out)
            } else {
                out.add(it)
            }
        }
    }

    /**
     * A game opened from another app (a frontend) arrives as one document. Its update and DLC may
     * be files in the same folder, which nothing registered yet when the app starts cold (no
     * library scan ran). Registers them, reaching the folder through the library folder that holds
     * it: the frontend's grant covers the game file only.
     */
    fun registerFolderContent(gameUri: Uri) {
        val folder = folderOf(gameUri) ?: return
        var registered = 0
        FileUtil.listFiles(folder).forEach { file ->
            if (!file.isDirectory && file.uri != gameUri &&
                CONTENT_CONTAINERS.contains(FileUtil.getExtension(file.uri).lowercase())
            ) {
                NativeLibrary.addFileToFilesystemProvider(file.uri.toString())
                registered++
            }
        }
        Log.info("[GameHelper] Game opened from another app: $registered files of its folder registered")
    }

    private val CONTENT_CONTAINERS = setOf("nsp", "xci", "nsz", "xcz")

    /** The folder holding [uri], as a URI this app may list, or null. */
    private fun folderOf(uri: Uri): Uri? = runCatching {
        if (uri.scheme != "content") {
            return null
        }
        val parentId = DocumentsContract.getDocumentId(uri).substringBeforeLast('/', "")
        if (parentId.isEmpty()) {
            return null
        }
        if (DocumentsContract.isTreeUri(uri)) {
            return DocumentsContract.buildDocumentUriUsingTree(uri, parentId)
        }
        val library = NativeConfig.getGameDirs().map { Uri.parse(it.uriString) }.firstOrNull { tree ->
            tree.authority == uri.authority && DocumentsContract.isTreeUri(tree) &&
                FileUtil.isTreeUriValid(tree) &&
                DocumentsContract.getTreeDocumentId(tree).let { root ->
                    parentId == root || parentId.startsWith(if (root.endsWith(":")) root else "$root/")
                }
        } ?: return null
        DocumentsContract.buildDocumentUriUsingTree(library, parentId)
    }.getOrNull()

    fun getGame(uri: Uri, addedToLibrary: Boolean): Game? {
        val filePath = uri.toString()
        if (!GameMetadata.getIsValid(filePath)) {
            return null
        }

        // Needed to update installed content information
        NativeLibrary.addFileToFilesystemProvider(filePath)

        var name = GameMetadata.getTitle(filePath)

        // If the game's title field is empty, use the filename.
        if (name.isEmpty()) {
            name = FileUtil.getFilename(uri)
        }
        var programId = GameMetadata.getProgramId(filePath)

        // If the game's ID field is empty, use the filename without extension.
        if (programId.isEmpty()) {
            programId = name.substring(0, name.lastIndexOf("."))
        }

        val newGame = Game(
            name,
            filePath,
            programId,
            GameMetadata.getDeveloper(filePath),
            GameMetadata.getVersion(filePath, false),
            GameMetadata.getIsHomebrew(filePath)
        )

        if (addedToLibrary) {
            val addedTime = preferences.getLong(newGame.keyAddedToLibraryTime, 0L)
            if (addedTime == 0L) {
                preferences.edit()
                    .putLong(newGame.keyAddedToLibraryTime, System.currentTimeMillis())
                    .apply()
            }
        }

        return newGame
    }
}
