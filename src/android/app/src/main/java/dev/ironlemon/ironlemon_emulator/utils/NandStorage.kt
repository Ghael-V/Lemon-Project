// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import android.content.Context
import android.os.storage.StorageManager
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.R
import java.io.File

/**
 * Where the emulated NAND (firmware, installed updates and DLC, saves) lives, and moving it to
 * another storage volume such as an SD card.
 *
 * Only the app's own folder on each volume is offered (Android/data/<package>/files), which the
 * app can write without asking for access to all files; it is also where the NAND is by default,
 * on the internal storage. Like that folder, it is removed with the app.
 */
object NandStorage {
    class Volume(val label: String, val nandDir: File, val freeBytes: Long)

    enum class MoveResult { MOVED, NO_SPACE, CANCELLED, FAILED }

    /** The NAND folder in use now. */
    val dir: String
        get() = NativeConfig.getNandDir().trimEnd('/')

    /** The storage volumes the NAND can be on, the internal storage first. */
    fun volumes(context: Context): List<Volume> {
        val storageManager = context.getSystemService(StorageManager::class.java)
        return context.getExternalFilesDirs(null).filterNotNull().mapIndexedNotNull { i, filesDir ->
            val nandDir = if (i == 0) {
                File(NativeConfig.getDefaultNandDir())
            } else {
                File(filesDir, "nand")
            }
            val label = storageManager?.getStorageVolume(filesDir)?.getDescription(context)
                ?: context.getString(
                    if (i == 0) R.string.nand_location_internal else R.string.nand_location_sd
                )
            Volume(label, nandDir, filesDir.usableSpace)
        }
    }

    /** The volume the NAND is on now, if it is one of [volumes]. */
    fun currentVolume(context: Context): Volume? {
        val current = File(dir).canonicalPath
        return volumes(context).firstOrNull { it.nandDir.canonicalPath == current }
    }

    fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Copies the NAND to [target], switches to it and deletes the old copy. [onProgress] gets the
     * bytes copied out of the total, and returns true to cancel. If the copy fails or is
     * cancelled, what it created is removed and the NAND stays where it was.
     */
    fun move(target: Volume, onProgress: (copied: Long, total: Long) -> Boolean): MoveResult {
        val source = File(dir)
        val destination = target.nandDir
        if (source.canonicalPath == destination.canonicalPath) {
            return MoveResult.MOVED
        }
        val total = if (source.exists()) sizeOf(source) else 0L
        if (total > target.freeBytes) {
            return MoveResult.NO_SPACE
        }

        val copy = Copy(total, onProgress)
        val copied = try {
            !source.exists() || copy.copy(source, destination)
        } catch (e: Exception) {
            Log.error("[NandStorage] Copy failed: ${e.message}")
            false
        }
        if (!copied) {
            copy.created.asReversed().forEach { it.delete() }
            return if (copy.cancelled) MoveResult.CANCELLED else MoveResult.FAILED
        }
        if (source.exists() && sizeOf(destination) < total) {
            Log.error("[NandStorage] The copy is smaller than the source, keeping the old NAND")
            return MoveResult.FAILED
        }

        if (!NativeConfig.setNandDir(destination.path)) {
            return MoveResult.FAILED
        }
        NativeConfig.saveGlobalConfig()
        // The content providers (firmware, updates, DLC) still point at the old folder.
        NativeLibrary.initializeSystem(true)
        source.deleteRecursively()
        return MoveResult.MOVED
    }

    /**
     * Back to the internal storage without copying anything: imported user data unpacks its NAND
     * there.
     */
    fun resetToDefault() {
        val default = NativeConfig.getDefaultNandDir()
        if (File(dir).canonicalPath != File(default).canonicalPath) {
            NativeConfig.setNandDir(default)
            NativeConfig.saveGlobalConfig()
        }
    }

    private class Copy(
        private val total: Long,
        private val onProgress: (Long, Long) -> Boolean
    ) {
        val created = ArrayList<File>()
        var cancelled = false
        private var copiedBytes = 0L
        private val buffer = ByteArray(1024 * 1024)

        fun copy(source: File, destination: File): Boolean {
            if (source.isDirectory) {
                if (!destination.exists()) {
                    if (!destination.mkdirs()) {
                        return false
                    }
                    created.add(destination)
                }
                val children = source.listFiles() ?: return false
                return children.all { copy(it, File(destination, it.name)) }
            }
            if (!destination.exists()) {
                created.add(destination)
            }
            source.inputStream().use { input ->
                destination.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        copiedBytes += read
                        if (onProgress(copiedBytes, total)) {
                            cancelled = true
                            return false
                        }
                    }
                }
            }
            return destination.length() == source.length()
        }
    }
}
