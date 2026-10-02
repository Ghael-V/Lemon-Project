// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.utils

import dev.lemon.lemon_emu.features.settings.utils.SettingsFile
import java.io.File
import java.io.IOException

/**
 * "Reset all settings": puts the global configuration back to stock.
 *
 * The configuration file also holds things that are not settings in the sense of this reset, and losing
 * them would hurt: the list of game folders (the library would empty), the save/NAND/SD locations (saves
 * would seem to vanish) and, for each game, which updates, DLC and mods are switched off. Those are read
 * before the file is deleted and written back afterwards. The add-on choices are only dropped when the
 * user also asks to delete the games' custom settings.
 */
object SettingsReset {
    /** Throws if the configuration could not be reset; nothing is deleted before everything was read. */
    fun resetAll(deleteGameSettings: Boolean) {
        val gameDirs = NativeConfig.getGameDirs()
        val saveDir = NativeConfig.getSaveDir()
        val nandDir = NativeConfig.getNandDir()
        val sdmcDir = NativeConfig.getSdmcDir()
        val disabledAddons = if (deleteGameSettings) {
            emptyMap()
        } else {
            GameHelper.getGames()
                .filter { it.programId.isNotEmpty() && it.programId != "0" }
                .associate { it.programId to NativeConfig.getDisabledAddons(it.programId) }
                .filterValues { it.isNotEmpty() }
        }

        NativeConfig.unloadGlobalConfig()
        try {
            // Delete the file, not just the known values: the user may have changed values that do not
            // exist in the UI.
            val settingsFile = SettingsFile.getSettingsFile(SettingsFile.FILE_NAME_CONFIG)
            if (settingsFile.exists() && !settingsFile.delete()) {
                throw IOException("Failed to delete $settingsFile")
            }
        } finally {
            NativeConfig.initializeGlobalConfig()
        }

        NativeConfig.setGameDirs(gameDirs)
        if (saveDir != NativeConfig.getSaveDir()) NativeConfig.setSaveDir(saveDir)
        if (nandDir != NativeConfig.getNandDir()) NativeConfig.setNandDir(nandDir)
        if (sdmcDir != NativeConfig.getSdmcDir()) NativeConfig.setSdmcDir(sdmcDir)
        disabledAddons.forEach { (programId, addons) -> NativeConfig.setDisabledAddons(programId, addons) }
        NativeConfig.saveGlobalConfig()

        if (deleteGameSettings) {
            deleteAllGameConfigs()
        }
    }

    private fun deleteAllGameConfigs() {
        val directory = File(DirectoryInitialization.userDirectory + "/config/custom")
        directory.listFiles { file -> file.isFile && file.extension == "ini" }?.forEach { file ->
            if (!file.delete()) {
                throw IOException("Failed to delete $file")
            }
        }
    }
}
