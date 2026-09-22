// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.lemon.lemon_emu.utils

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.view.Surface
import java.io.File
import java.io.IOException
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.LemonApplication
import dev.lemon.lemon_emu.features.settings.model.StringSetting
import java.io.FileNotFoundException
import java.util.zip.ZipException
import java.util.zip.ZipFile

object GpuDriverHelper {
    private const val META_JSON_FILENAME = "meta.json"
    private var fileRedirectionPath: String? = null
    var driverInstallationPath: String? = null
    internal var hookLibPath: String? = null

    val driverStoragePath get() = DirectoryInitialization.userDirectory!! + "/gpu_drivers/"

    fun initializeFreedrenoConfigEarly() {
        NativeFreedrenoConfig.setFreedrenoBasePath(LemonApplication.appContext.cacheDir.absolutePath)
        NativeFreedrenoConfig.initializeFreedrenoConfig()
        NativeFreedrenoConfig.reloadFreedrenoConfig()
    }

    @Volatile
    private var gpuDriverLoaded = false

    fun initializeDriverParameters() {
        try {
            // Initialize the file redirection directory.
            fileRedirectionPath = LemonApplication.appContext
                .getExternalFilesDir(null)!!.canonicalPath + "/gpu/vk_file_redirect/"

            // Initialize the driver installation directory.
            driverInstallationPath = LemonApplication.appContext
                .filesDir.canonicalPath + "/gpu_driver/"
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

        initializeDirectories()
        hookLibPath = LemonApplication.appContext.applicationInfo.nativeLibraryDir + "/"
        NativeFreedrenoConfig.reloadFreedrenoConfig()

        // The actual adrenotools_open_libvulkan() call used to happen right here, at every
        // app cold start, before any UI is shown - reported to crash repeatedly (no ANR, no
        // log output, self-resolves after a few attempts) on a Snapdragon 888/Adreno 660
        // device. That pattern points at a driver-load race that's orthogonal to device
        // performance, not something waiting helps with - just something that shouldn't be
        // gating app startup for every single launch when no game is even running yet. Moved
        // to ensureGpuDriverLoaded(), called once lazily right before emulation actually
        // starts (see EmulationFragment.runWithValidSurface()).
    }

    // Actually opens the Vulkan driver via adrenotools - the one call in this file that can
    // crash on flaky driver/KGSL combinations. Guarded to run at most once per process: by
    // design this only needs to happen before the first game starts, not on every launch of a
    // new game within the same app session.
    fun ensureGpuDriverLoaded() {
        if (gpuDriverLoaded) {
            return
        }
        synchronized(this) {
            if (gpuDriverLoaded) {
                return
            }
            NativeLibrary.initializeGpuDriver(
                hookLibPath,
                driverInstallationPath,
                installedCustomDriverData.libraryName,
                fileRedirectionPath
            )
            gpuDriverLoaded = true
        }
    }

    fun getDrivers(): MutableList<Pair<String, GpuDriverMetadata>> {
        val driverZips = File(driverStoragePath).listFiles()
        val drivers: MutableList<Pair<String, GpuDriverMetadata>> =
            driverZips
                ?.mapNotNull {
                    val metadata = getMetadataFromZip(it)
                    metadata.name?.let { _ -> Pair(it.path, metadata) }
                }
                ?.sortedByDescending { it: Pair<String, GpuDriverMetadata> -> it.second.name }
                ?.distinct()
                ?.toMutableList() ?: mutableListOf()
        return drivers
    }

    fun installDefaultDriver() {
        // Removing the installed driver will result in the backend using the default system driver.
        File(driverInstallationPath!!).deleteRecursively()
        initializeDriverParameters()
    }

    fun copyDriverToInternalStorage(driverUri: Uri): Boolean {
        // Ensure we have directories.
        initializeDirectories()

        // Copy the zip file URI to user data
        val copiedFile =
            FileUtil.copyUriToInternalStorage(driverUri, driverStoragePath) ?: return false

        // Validate driver
        val metadata = getMetadataFromZip(copiedFile)
        if (metadata.name == null) {
            copiedFile.delete()
            return false
        }

        if (metadata.minApi > Build.VERSION.SDK_INT) {
            copiedFile.delete()
            return false
        }
        return true
    }

    /**
     * Copies driver zip into user data directory so that it can be exported along with
     * other user data and also unzipped into the installation directory
     */
    fun installCustomDriver(driverUri: Uri): Boolean {
        // Revert to system default in the event the specified driver is bad.
        installDefaultDriver()

        // Ensure we have directories.
        initializeDirectories()

        // Copy the zip file URI to user data
        val copiedFile =
            FileUtil.copyUriToInternalStorage(driverUri, driverStoragePath) ?: return false

        // Validate driver
        val metadata = getMetadataFromZip(copiedFile)
        if (metadata.name == null) {
            copiedFile.delete()
            return false
        }

        if (metadata.minApi > Build.VERSION.SDK_INT) {
            copiedFile.delete()
            return false
        }

        // Unzip the driver.
        try {
            FileUtil.unzipToInternalStorage(
                copiedFile.path,
                File(driverInstallationPath!!)
            )
        } catch (e: SecurityException) {
            return false
        }

        // Initialize the driver parameters.
        initializeDriverParameters()

        return true
    }

    /**
     * Unzips driver into installation directory
     */
    fun installCustomDriver(driver: File): Boolean {
        // Revert to system default in the event the specified driver is bad.
        installDefaultDriver()

        // Ensure we have directories.
        initializeDirectories()

        // Validate driver
        val metadata = getMetadataFromZip(driver)
        if (metadata.name == null) {
            driver.delete()
            return false
        }

        // Unzip the driver to the private installation directory
        try {
            FileUtil.unzipToInternalStorage(
                driver.path,
                File(driverInstallationPath!!)
            )
        } catch (e: SecurityException) {
            return false
        }

        // Initialize the driver parameters.
        initializeDriverParameters()

        return true
    }

    /**
     * Takes in a zip file and reads the meta.json file for presentation to the UI
     *
     * @param driver Zip containing driver and meta.json file
     * @return A non-null [GpuDriverMetadata] instance that may have null members
     */
    fun getMetadataFromZip(driver: File): GpuDriverMetadata {
        if (!driver.exists()) {
            return GpuDriverMetadata()
        }

        try {
            ZipFile(driver).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.lowercase().contains(".json")) {
                        zf.getInputStream(entry).use {
                            return GpuDriverMetadata(it, entry.size)
                        }
                    }
                }
            }
        } catch (_: ZipException) {
        } catch (_: FileNotFoundException) {
        }
        return GpuDriverMetadata()
    }

    external fun supportsCustomDriverLoading(): Boolean

    external fun getSystemDriverInfo(
        surface: Surface = Surface(SurfaceTexture(true)),
        hookLibPath: String = GpuDriverHelper.hookLibPath!!
    ): Array<String>?

    external fun getGpuModel(
        surface: Surface = Surface(SurfaceTexture(true)),
        hookLibPath: String
    ): String?

    fun isAdrenoGpu(): Boolean {
        return try {
            supportsCustomDriverLoading()
        } catch (e: Throwable) {
            // Catches Throwable (not just Exception): a call before the native library is
            // fully linked throws UnsatisfiedLinkError, which is an Error and was previously
            // escaping this uncaught.
            false
        }
    }

    // Parse the custom driver metadata to retrieve the name.
    val installedCustomDriverData: GpuDriverMetadata
        get() = GpuDriverMetadata(File(driverInstallationPath + META_JSON_FILENAME))

    val customDriverSettingData: GpuDriverMetadata
        get() = getMetadataFromZip(File(StringSetting.DRIVER_PATH.getString()))

    fun initializeDirectories() {
        // Ensure the file redirection directory exists.
        val fileRedirectionDir = File(fileRedirectionPath!!)
        if (!fileRedirectionDir.exists()) {
            fileRedirectionDir.mkdirs()
        }
        // Ensure the driver installation directory exists.
        val driverInstallationDir = File(driverInstallationPath!!)
        if (!driverInstallationDir.exists()) {
            driverInstallationDir.mkdirs()
        }
        // Ensure the driver storage directory exists
        val driverStorageDirectory = File(driverStoragePath)
        if (!driverStorageDirectory.exists()) {
            driverStorageDirectory.mkdirs()
        }
    }

    /**
     * Checks if a driver zip with the given filename is already present and valid in the
     * internal driver storage directory. Validation requires a readable meta.json with a name.
     */
    fun isDriverZipInstalledByName(fileName: String): Boolean {
        // Normalize separators in case upstream sent a path
        val baseName = fileName.substringAfterLast('/')
            .substringAfterLast('\\')
        val candidate = File("$driverStoragePath$baseName")
        if (!candidate.exists() || candidate.length() == 0L) return false
        val metadata = getMetadataFromZip(candidate)
        return metadata.name != null
    }
}
