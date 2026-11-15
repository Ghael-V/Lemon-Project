// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dev.ironlemon.ironlemon_emulator.YuzuApplication
import dev.ironlemon.ironlemon_emulator.features.settings.model.StringSetting
import java.io.File

/**
 * The Lemon-Ade Turnip driver shipped inside the APK (the one zip in assets/bundled_driver).
 *
 * - Its zip is copied into the driver storage on every start, so it always shows up in the driver
 *   manager like any installed driver.
 * - It is selected automatically only once, on Snapdragon (Adreno 6xx and newer, the GPUs Turnip
 *   drives), when the user is still on the system driver. Any driver picked afterwards is left alone.
 * - Until one game has rendered a frame with it, each game launch leaves a marker that the first
 *   frame clears. A marker still present at the next start means the app died before that first
 *   frame, so the global driver goes back to the system one and the user is told.
 * - When a newer APK ships a newer zip, users still on the previous bundled zip move to the new one.
 *
 * After Lemon's BundledDriver (Lemon 15359f10a0, 79caf4832b).
 */
object BundledDriver {
    private const val ASSET_DIR = "bundled_driver"

    // Turnip drives the Adreno 6xx, 7xx and 8xx families.
    private const val MIN_ADRENO_MODEL = 600

    private const val PREF_DECIDED = "bundled_driver_default_decided"
    private const val PREF_VERIFIED = "bundled_driver_verified"
    private const val PREF_BOOT_PENDING = "bundled_driver_boot_pending"
    private const val PREF_FAILED = "bundled_driver_failed"
    private const val PREF_INSTALLED_NAME = "bundled_driver_installed_name"

    private val preferences
        get() = PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    /** File name of the zip inside the APK, e.g. "Lemon-Ade-v0.0.7.zip", or null if none. */
    private fun assetName(context: Context): String? =
        context.assets.list(ASSET_DIR)?.firstOrNull { it.endsWith(".zip", ignoreCase = true) }

    private fun storagePath(name: String) = GpuDriverHelper.driverStoragePath + name

    /** Path the bundled driver is installed at, or null if this build doesn't carry one. */
    fun installedPath(context: Context = YuzuApplication.appContext): String? =
        assetName(context)?.let { storagePath(it) }

    private fun isSelectedGlobally(path: String?): Boolean =
        path != null && StringSetting.DRIVER_PATH.getString(needsGlobal = true) == path

    /**
     * Runs off the main thread at app start. Returns true if a previous launch with the bundled
     * driver never reached its first frame and the global driver was switched back to the system
     * one, so the caller can tell the user.
     */
    fun onAppStart(context: Context): Boolean {
        val name = assetName(context) ?: return false
        val path = storagePath(name)
        GpuDriverHelper.initializeDirectories()
        val target = File(path)
        if (!target.exists() || target.length() == 0L) {
            context.assets.open("$ASSET_DIR/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }

        // A launch with the unverified bundled driver died before rendering anything.
        if (preferences.getBoolean(PREF_BOOT_PENDING, false)) {
            preferences.edit {
                putBoolean(PREF_BOOT_PENDING, false)
                putBoolean(PREF_FAILED, true)
                putBoolean(PREF_DECIDED, true)
            }
            if (isSelectedGlobally(path)) {
                StringSetting.DRIVER_PATH.setString("")
                NativeConfig.saveGlobalConfig()
                Log.warning("[BundledDriver] $name never reached a first frame, back to the system driver")
                return true
            }
            return false
        }

        // A newer APK brought a newer bundled zip: move users who were on the old one.
        val previousName = preferences.getString(PREF_INSTALLED_NAME, null)
        if (previousName != null && previousName != name) {
            if (isSelectedGlobally(storagePath(previousName))) {
                StringSetting.DRIVER_PATH.setString(path)
                NativeConfig.saveGlobalConfig()
                preferences.edit { putBoolean(PREF_VERIFIED, false) }
                Log.info("[BundledDriver] Updated the selected bundled driver $previousName -> $name")
            }
            File(storagePath(previousName)).delete()
        }
        preferences.edit { putString(PREF_INSTALLED_NAME, name) }

        if (preferences.getBoolean(PREF_DECIDED, false)) {
            return false
        }

        // Never override a driver the user already chose.
        if (StringSetting.DRIVER_PATH.getString(needsGlobal = true).isNotEmpty()) {
            preferences.edit { putBoolean(PREF_DECIDED, true) }
            return false
        }
        val model = adrenoModel() ?: return false // No answer from the driver yet: next start.
        preferences.edit { putBoolean(PREF_DECIDED, true) }
        if (model < MIN_ADRENO_MODEL) {
            return false
        }
        StringSetting.DRIVER_PATH.setString(path)
        NativeConfig.saveGlobalConfig()
        Log.info("[BundledDriver] Selected $name by default on Adreno $model")
        return false
    }

    /** Called right before a game starts, once the driver for it has been decided. */
    fun onEmulationStarting() {
        val path = installedPath() ?: return
        if (StringSetting.DRIVER_PATH.getString() != path ||
            preferences.getBoolean(PREF_VERIFIED, false)
        ) {
            return
        }
        // commit(), not apply(): this has to be on disk before the driver can take the app down.
        preferences.edit(commit = true) { putBoolean(PREF_BOOT_PENDING, true) }
    }

    /** The game kept rendering with it for a while: the bundled driver works on this device. */
    fun onFirstFrame() {
        if (preferences.getBoolean(PREF_BOOT_PENDING, false)) {
            preferences.edit {
                putBoolean(PREF_BOOT_PENDING, false)
                putBoolean(PREF_VERIFIED, true)
            }
        }
    }

    /** Emulation stopped normally before a first frame: not a driver failure. */
    fun onEmulationStopped() {
        if (preferences.getBoolean(PREF_BOOT_PENDING, false)) {
            preferences.edit { putBoolean(PREF_BOOT_PENDING, false) }
        }
    }

    /**
     * The Adreno model number from the system driver's device name ("Adreno (TM) 830"): 0 for a
     * GPU that is not an Adreno, null when the driver could not be asked.
     */
    private fun adrenoModel(): Int? {
        val name = GpuDriverHelper.querySystemDriverInfo()?.getOrNull(1) ?: return null
        val match = Regex("""Adreno\D*(\d{3})""").find(name) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }
}
