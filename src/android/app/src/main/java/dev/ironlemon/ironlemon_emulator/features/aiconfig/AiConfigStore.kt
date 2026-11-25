// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.features.aiconfig

import android.os.Handler
import android.os.Looper
import android.os.Process
import dev.ironlemon.ironlemon_emulator.features.settings.utils.SettingsFile
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.utils.DirectoryInitialization
import dev.ironlemon.ironlemon_emulator.utils.Log
import dev.ironlemon.ironlemon_emulator.utils.NativeConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Applies an AI suggestion to a game's own config and keeps what is needed to take it back:
 * - Before the first suggestion, a copy of the game's config (or a note that it had none). Later
 *   suggestions keep that first copy, so undo always returns to the config before any AI change.
 * - A state file. When the game starts with the suggestion it becomes "launching" with this
 *   process's id. If the process ends before the game has run [CONFIRM_AFTER_MS], the next start of
 *   the app finds it, restores the copy and reports the crash to the server.
 *
 * Files live in config/custom/ai/, next to the per-game configs, named after Game.settingsName.
 */
object AiConfigStore {
    private const val CONFIRM_AFTER_MS = 90_000L
    private const val NO_CONFIG = "# no per-game config before the AI change\n"

    @Serializable
    data class State(
        val answer_id: String,
        val applied_at: Long,
        val state: String = APPLIED,
        val pid: Int = 0,
        /** Set when the last copy was put back because the game closed; shown once. */
        val notice: String? = null
    )

    const val APPLIED = "applied"
    const val LAUNCHING = "launching"
    const val CONFIRMED = "confirmed"
    const val REVERTED_CRASH = "reverted_crash"

    /** Set by the emulation screen: false when this launch uses the global config. */
    @Volatile
    var launchUsesCustomConfig = true

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var confirmTask: Runnable? = null
    @Volatile
    private var startedGame: Game? = null

    private fun aiDir(userDir: File) = File(userDir, "config/custom/ai").apply { mkdirs() }
    private fun configFile(userDir: File, name: String) = File(userDir, "config/custom/$name.ini")
    private fun backupFile(userDir: File, name: String) = File(aiDir(userDir), "$name.backup.ini")
    private fun stateFile(userDir: File, name: String) = File(aiDir(userDir), "$name.json")

    private fun userDir() = File(DirectoryInitialization.userDirectory!!)

    private fun readState(file: File): State? =
        runCatching { json.decodeFromString(State.serializer(), file.readText()) }.getOrNull()

    private fun writeState(file: File, state: State) =
        file.writeText(json.encodeToString(State.serializer(), state))

    fun state(game: Game): State? = readState(stateFile(userDir(), game.settingsName))

    /** True while an AI change can be undone for [game]. */
    fun hasActive(game: Game): Boolean =
        runCatching { state(game) }.getOrNull()?.let { it.state != REVERTED_CRASH } ?: false

    /**
     * Writes [values] (key -> config.ini text, already checked against the schema) into [game]'s
     * own config. Must not run while that game is running.
     */
    @Synchronized
    fun apply(game: Game, answerId: String, values: Map<String, String>) {
        val dir = userDir()
        val name = game.settingsName
        val backup = backupFile(dir, name)
        val previous = readState(stateFile(dir, name))
        if (!backup.exists() || previous == null || previous.state == REVERTED_CRASH) {
            val config = configFile(dir, name)
            if (config.exists()) config.copyTo(backup, overwrite = true) else backup.writeText(NO_CONFIG)
        }
        SettingsFile.loadCustomConfig(game)
        try {
            values.forEach { (key, value) ->
                NativeConfig.setGlobal(key, false)
                if (!NativeConfig.setValueString(key, value)) {
                    Log.warning("[AiConfig] Unknown setting $key, skipped")
                }
            }
            NativeConfig.savePerGameConfig()
        } finally {
            NativeConfig.unloadPerGameConfig()
        }
        writeState(stateFile(dir, name), State(answerId, System.currentTimeMillis()))
        Log.info("[AiConfig] Applied ${values.size} settings to $name")
    }

    /** Puts [game]'s config back as it was before the AI changes. */
    @Synchronized
    fun undo(game: Game, report: Boolean = true) {
        val answerId = restore(userDir(), game.settingsName)
        if (report && answerId != null) {
            scope.launch { AiConfigClient.feedback(answerId, AiConfigClient.Outcome.REVERTED) }
        }
    }

    /** Restores the copy and drops the AI files. Returns the answer id that was undone. */
    private fun restore(dir: File, name: String): String? {
        val stateFile = stateFile(dir, name)
        val state = readState(stateFile)
        val backup = backupFile(dir, name)
        val config = configFile(dir, name)
        if (backup.exists()) {
            if (backup.readText() == NO_CONFIG) config.delete() else backup.copyTo(config, overwrite = true)
            backup.delete()
        }
        stateFile.delete()
        Log.info("[AiConfig] Restored the config of $name from before the AI change")
        return state?.answer_id
    }

    /** Clears the "reverted after a crash" note once the person has seen it. */
    fun clearNotice(game: Game) {
        val file = stateFile(userDir(), game.settingsName)
        if (readState(file)?.state == REVERTED_CRASH) file.delete()
    }

    /**
     * At the app's start: a game that was "launching" with an AI change in an earlier process did not
     * survive it. Put its config back and tell the server.
     */
    fun recoverAfterCrash(userDir: File) {
        val files = File(userDir, "config/custom/ai").listFiles { f -> f.name.endsWith(".json") }
            ?: return
        for (file in files) {
            val state = readState(file) ?: continue
            if (state.state != LAUNCHING || state.pid == Process.myPid()) continue
            val name = file.name.removeSuffix(".json")
            Log.warning("[AiConfig] $name closed while starting with the AI settings; undoing them")
            restore(userDir, name)
            writeState(file, state.copy(state = REVERTED_CRASH, notice = "crashed"))
            scope.launch { AiConfigClient.feedback(state.answer_id, AiConfigClient.Outcome.CRASHED) }
        }
    }

    /** Called when [game]'s emulation has started. */
    fun onGameStarted(game: Game) {
        startedGame = game
        val file = runCatching { stateFile(userDir(), game.settingsName) }.getOrNull() ?: return
        val state = readState(file) ?: return
        if (state.state != APPLIED || !launchUsesCustomConfig) return
        writeState(file, state.copy(state = LAUNCHING, pid = Process.myPid()))
        confirmTask?.let { mainHandler.removeCallbacks(it) }
        confirmTask = Runnable {
            val now = readState(file) ?: return@Runnable
            if (now.state == LAUNCHING && now.pid == Process.myPid()) {
                writeState(file, now.copy(state = CONFIRMED))
                Log.info("[AiConfig] ${game.settingsName} ran fine with the AI settings")
            }
        }.also { mainHandler.postDelayed(it, CONFIRM_AFTER_MS) }
    }

    /** Called when the emulation stopped normally: an early exit is not a failure. */
    fun onGameStopped(game: Game? = startedGame) {
        confirmTask?.let { mainHandler.removeCallbacks(it) }
        confirmTask = null
        startedGame = null
        game ?: return
        val file = runCatching { stateFile(userDir(), game.settingsName) }.getOrNull() ?: return
        val state = readState(file) ?: return
        if (state.state == LAUNCHING) writeState(file, state.copy(state = APPLIED, pid = 0))
    }
}
