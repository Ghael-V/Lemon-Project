// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.model

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.YuzuApplication
import dev.ironlemon.ironlemon_emulator.utils.GameHelper
import dev.ironlemon.ironlemon_emulator.utils.NativeConfig
import java.util.concurrent.atomic.AtomicBoolean

class GamesViewModel : ViewModel() {
    val games: StateFlow<List<Game>> get() = _games
    private val _games = MutableStateFlow(emptyList<Game>())

    val searchedGames: StateFlow<List<Game>> get() = _searchedGames
    private val _searchedGames = MutableStateFlow(emptyList<Game>())

    val isReloading: StateFlow<Boolean> get() = _isReloading
    private val _isReloading = MutableStateFlow(false)

    private val reloading = AtomicBoolean(false)

    val shouldSwapData: StateFlow<Boolean> get() = _shouldSwapData
    private val _shouldSwapData = MutableStateFlow(false)

    val shouldScrollToTop: StateFlow<Boolean> get() = _shouldScrollToTop
    private val _shouldScrollToTop = MutableStateFlow(false)

    val searchFocused: StateFlow<Boolean> get() = _searchFocused
    private val _searchFocused = MutableStateFlow(false)

    private val _folders = MutableStateFlow(mutableListOf<GameDir>())
    val folders = _folders.asStateFlow()

    init {
        // Ensure keys are loaded so that ROM metadata can be decrypted.
        NativeLibrary.reloadKeys()

        getGameDirs()
        reloadGames(directoriesChanged = false, firstStartup = true)
    }

    fun setGames(games: List<Game>) {
        val sortedList = games.sortedWith(
            compareBy(
                { it.title.lowercase(Locale.getDefault()) },
                { it.path }
            )
        )

        _games.value = sortedList
    }

    fun setSearchedGames(games: List<Game>) {
        _searchedGames.value = games
    }

    fun setShouldSwapData(shouldSwap: Boolean) {
        _shouldSwapData.value = shouldSwap
    }

    fun setShouldScrollToTop(shouldScroll: Boolean) {
        _shouldScrollToTop.value = shouldScroll
    }

    fun setSearchFocused(searchFocused: Boolean) {
        _searchFocused.value = searchFocused
    }

    fun reloadGames(directoriesChanged: Boolean, firstStartup: Boolean = false) {
        if (!reloading.compareAndSet(false, true)) {
            return
        }
        _isReloading.value = true

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (firstStartup) {
                    // Retrieve list of cached games
                    val storedGames =
                        PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)
                            .getStringSet(GameHelper.KEY_GAMES, emptySet())
                    if (storedGames!!.isNotEmpty()) {
                        val deserializedGames = mutableSetOf<Game>()
                        storedGames.forEach {
                            val game: Game
                            try {
                                game = Json.decodeFromString(it)
                            } catch (e: Exception) {
                                // We don't care about any errors related to parsing the game cache
                                return@forEach
                            }

                            val gameExists =
                                DocumentFile.fromSingleUri(
                                    YuzuApplication.appContext,
                                    Uri.parse(game.path)
                                )?.exists()
                            if (gameExists == true) {
                                deserializedGames.add(game)
                            }
                        }
                        setGames(deserializedGames.toList())
                        // The app was already open (the library came back after a game): the
                        // cached list is the scan from a moment ago, no need to walk every folder
                        // again. A refresh or a folder change still rescans.
                        if (scannedThisSession && deserializedGames.isNotEmpty()) {
                            reloading.set(false)
                            _isReloading.value = false
                            return@withContext
                        }
                    }
                }

                setGames(GameHelper.getGames())
                scannedThisSession = true
                reloading.set(false)
                _isReloading.value = false

                if (directoriesChanged) {
                    setShouldSwapData(true)
                }
            }
        }
    }

    fun addFolder(gameDir: GameDir) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                NativeConfig.addGameDir(gameDir)
                // Saved now, not when the screen goes away: the app can be killed before that.
                NativeConfig.saveGlobalConfig()
                getGameDirs(true)
            }
        }

    fun removeFolder(gameDir: GameDir) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val gameDirs = _folders.value.toMutableList()
                val removedDirIndex = gameDirs.indexOf(gameDir)
                if (removedDirIndex != -1) {
                    gameDirs.removeAt(removedDirIndex)
                    NativeConfig.setGameDirs(gameDirs.toTypedArray())
                    NativeConfig.saveGlobalConfig()
                    getGameDirs()
                }
            }
        }

    fun updateGameDirs() =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                NativeConfig.setGameDirs(_folders.value.toTypedArray())
                NativeConfig.saveGlobalConfig()
                getGameDirs()
            }
        }

    fun onOpenGameFoldersFragment() =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                getGameDirs()
            }
        }

    fun onCloseGameFoldersFragment() {
        NativeConfig.saveGlobalConfig()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                getGameDirs(true)
            }
        }
    }

    private fun getGameDirs(reloadList: Boolean = false) {
        val gameDirs = NativeConfig.getGameDirs()
        _folders.value = gameDirs.toMutableList()
        if (reloadList) {
            reloadGames(true)
        }
    }

    companion object {
        /** Whether the games were scanned since the app's process started. */
        @Volatile
        private var scannedThisSession = false

        /** The library opens after a game: show the stored list, as if it had just been scanned. */
        fun skipStartupScan() {
            scannedThisSession = true
        }
    }
}

