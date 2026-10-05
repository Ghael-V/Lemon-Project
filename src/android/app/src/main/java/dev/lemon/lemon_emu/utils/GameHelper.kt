// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.lemon.lemon_emu.utils

import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.LemonApplication
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.model.GameDir
import dev.lemon.lemon_emu.model.MinimalDocumentFile
import androidx.core.content.edit
import androidx.core.net.toUri
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

    var cachedGameList = mutableListOf<Game>()

    private lateinit var preferences: SharedPreferences

    /** Non-null while a scan is running, so the library can show that work is going on. */
    val scanProgress: StateFlow<ScanProgress?> get() = _scanProgress
    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)

    /**
     * Rebuilds the emulator's file system and content providers (after installing or removing
     * firmware, or importing data). Waits for a library scan in progress to end first: rebuilding
     * frees the content providers the scan is reading, which crashed the app (SIGBUS in
     * ContentProviderUnion::GetEntryRaw). Call it off the main thread, a scan can take minutes.
     */
    @Synchronized
    fun reinitializeSystem() {
        NativeLibrary.initializeSystem(true)
    }

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

    private fun scanGames(): List<Game> {
        val games = mutableListOf<Game>()
        val gamesByProgramId = mutableMapOf<String, Game>()
        val context = LemonApplication.appContext
        preferences = PreferenceManager.getDefaultSharedPreferences(context)

        val gameDirs = mutableListOf<GameDir>()
        val oldGamesDir = preferences.getString(KEY_OLD_GAME_PATH, "") ?: ""
        if (oldGamesDir.isNotEmpty()) {
            gameDirs.add(GameDir(oldGamesDir, true))
            preferences.edit() { remove(KEY_OLD_GAME_PATH) }
        }
        gameDirs.addAll(NativeConfig.getGameDirs())

        // Ensure keys are loaded so that ROM metadata can be decrypted.
        NativeLibrary.reloadKeys()

        // Reset metadata so we don't use stale information
        GameMetadata.resetMetadata()

        // Remove previous filesystem provider information so we can get up to date version info
        NativeLibrary.clearFilesystemProvider()

        val mountedContainerUris = mutableSetOf<String>()
        mountExternalContentDirectories(mountedContainerUris)

        val badDirs = mutableListOf<Int>()
        // List every folder first (quick), so the scan below knows how many files it has to read
        // and can report real progress. Files keep the order the old recursive walk visited them.
        val pending = mutableListOf<MinimalDocumentFile>()
        gameDirs.forEachIndexed { index: Int, gameDir: GameDir ->
            val gameDirUri = gameDir.uriString.toUri()
            val isValid = FileUtil.isTreeUriValid(gameDirUri)
            if (isValid) {
                val scanDepth = if (gameDir.deepScan) 3 else 1

                collectFiles(FileUtil.listFiles(gameDirUri), scanDepth, pending)
            } else {
                badDirs.add(index)
            }
        }
        scanFiles(pending, games, gamesByProgramId, mountedContainerUris)

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
        val serializedGames = mutableSetOf<String>()
        games.forEach {
            serializedGames.add(Json.encodeToString(it))
        }
        preferences.edit() {
            remove(KEY_GAMES)
                .putStringSet(KEY_GAMES, serializedGames)
        }

        cachedGameList = games.toMutableList()
        return games.toList()
    }

    fun restoreContentForGame(game: Game) {
        NativeLibrary.reloadKeys()

        val mountedContainerUris = mutableSetOf<String>()
        mountExternalContentDirectories(mountedContainerUris)
        mountGameFolderContent(Uri.parse(game.path), mountedContainerUris)
        NativeLibrary.addFileToFilesystemProvider(game.path)
    }

    // File extensions considered as external content, buuut should
    // be done better imo.
    private val externalContentExtensions = setOf("nsp", "xci", "nsz", "xcz")

    private fun scanContentContainersRecursive(
        files: Array<MinimalDocumentFile>,
        depth: Int,
        onContainerFound: (MinimalDocumentFile) -> Unit
    ) {
        if (depth <= 0) {
            return
        }

        files.forEach {
            if (it.isDirectory) {
                scanContentContainersRecursive(
                    FileUtil.listFiles(it.uri),
                    depth - 1,
                    onContainerFound
                )
            } else {
                val extension = FileUtil.getExtension(it.uri).lowercase()
                if (externalContentExtensions.contains(extension)) {
                    onContainerFound(it)
                }
            }
        }
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

    private fun scanFiles(
        files: List<MinimalDocumentFile>,
        games: MutableList<Game>,
        gamesByProgramId: MutableMap<String, Game>,
        mountedContainerUris: MutableSet<String>
    ) {
        fun isRelevant(extension: String) =
            externalContentExtensions.contains(extension) || Game.extensions.contains(extension)

        val total = files.count { isRelevant(FileUtil.getExtension(it.uri).lowercase()) }
        var done = 0
        files.forEach {
            val extension = FileUtil.getExtension(it.uri).lowercase()
            if (!isRelevant(extension)) {
                return@forEach
            }
            _scanProgress.value = ScanProgress(done, total, it.filename)
            addGameFile(it, extension, games, gamesByProgramId, mountedContainerUris)
            done++
        }
    }

    private fun addGameFile(
        file: MinimalDocumentFile,
        extension: String,
        games: MutableList<Game>,
        gamesByProgramId: MutableMap<String, Game>,
        mountedContainerUris: MutableSet<String>
    ) {
        val filePath = file.uri.toString()

        val mountedContainer = externalContentExtensions.contains(extension) &&
            mountedContainerUris.add(filePath)
        if (mountedContainer) {
            NativeLibrary.addGameFolderFileToFilesystemProvider(filePath)
        }

        if (Game.extensions.contains(extension)) {
            val game = getGame(file.uri, true, false)
            if (game != null) {
                games.add(game)
                if (game.programId != "0") {
                    gamesByProgramId[game.programId] = game
                }
            } else if (mountedContainer) {
                GameMetadata.getProgramId(filePath).toLongOrNull()?.let { programId ->
                    gamesByProgramId[(programId and 0x800L.inv()).toString()]
                }?.let { existingGame ->
                    NativeLibrary.getPatchesForFile(existingGame.path, existingGame.programId)
                    existingGame.version = GameMetadata.getVersion(
                        existingGame.path,
                        true
                    )
                    GameIconUtils.refreshGameIcon(existingGame)
                }
            }
        }
    }

    private fun mountExternalContentDirectories(mountedContainerUris: MutableSet<String>) {
        val uniqueExternalContentDirs = linkedSetOf<String>()
        NativeConfig.getExternalContentDirs().forEach { externalDir ->
            if (externalDir.isNotEmpty()) {
                uniqueExternalContentDirs.add(externalDir)
            }
        }

        for (externalDir in uniqueExternalContentDirs) {
            val externalDirUri = externalDir.toUri()
            if (FileUtil.isTreeUriValid(externalDirUri)) {
                scanContentContainersRecursive(FileUtil.listFiles(externalDirUri), 3) {
                    val containerUri = it.uri.toString()
                    if (mountedContainerUris.add(containerUri)) {
                        NativeLibrary.addFileToFilesystemProvider(containerUri)
                    }
                }
            }
        }
    }

    private fun mountGameFolderContent(gameUri: Uri, mountedContainerUris: MutableSet<String>) {
        if (gameUri.scheme == "content") {
            val parentUri = getParentDocumentUri(gameUri) ?: return
            scanContentContainersRecursive(FileUtil.listFiles(parentUri), 1) {
                val containerUri = it.uri.toString()
                if (mountedContainerUris.add(containerUri)) {
                    NativeLibrary.addGameFolderFileToFilesystemProvider(containerUri)
                }
            }
            return
        }

        val gameFile = File(gameUri.path ?: gameUri.toString())
        val parentDir = gameFile.parentFile ?: return
        parentDir.listFiles()?.forEach { sibling ->
            if (!sibling.isFile) {
                return@forEach
            }

            val extension = sibling.extension.lowercase()
            if (externalContentExtensions.contains(extension)) {
                val containerUri = Uri.fromFile(sibling).toString()
                if (mountedContainerUris.add(containerUri)) {
                    NativeLibrary.addGameFolderFileToFilesystemProvider(containerUri)
                }
            }
        }
    }

    private fun getParentDocumentUri(uri: Uri): Uri? {
        return try {
            val documentId = DocumentsContract.getDocumentId(uri)
            val separatorIndex = documentId.lastIndexOf('/')
            if (separatorIndex == -1) {
                null
            } else {
                val parentDocumentId = documentId.substring(0, separatorIndex)
                DocumentsContract.buildDocumentUriUsingTree(uri, parentDocumentId)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getGame(
        uri: Uri,
        addedToLibrary: Boolean,
        registerFilesystemProvider: Boolean = true
    ): Game? {
        val filePath = uri.toString()
        if (!GameMetadata.getIsValid(filePath)) {
            return null
        }

        if (registerFilesystemProvider) {
            // Needed to update installed content information
            NativeLibrary.addFileToFilesystemProvider(filePath)
        }

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
