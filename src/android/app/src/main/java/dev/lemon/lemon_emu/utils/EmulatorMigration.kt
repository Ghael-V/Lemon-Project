// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.lemon.lemon_emu.NativeLibrary
import java.io.File
import java.io.FileNotFoundException
import java.math.BigInteger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Brings save data (plus keys and firmware, if Lemon has none yet) over from another yuzu-family
 * emulator (Eden, Citron, yuzu, other Lemon builds...) without opening it.
 *
 * Android doesn't let one app read another's private storage, but every yuzu-family emulator
 * publishes its user folder through the same DocumentsProvider (authority "<package>.user",
 * document IDs "root/<relative path>"). The user grants access to that folder once in the system
 * folder picker, and Lemon reads it from there.
 */
object EmulatorMigration {
    private const val PROVIDER_ROOT_ID = "root"
    private val SAVE_LAYOUT_DIRS = listOf(
        "root/nand/user/save/0000000000000000", // <user id>/<title id>
        "root/nand/user/save/account" // <user uuid>/<title id>, newer layout
    )
    private const val KEYS_DOCUMENT_ID = "root/keys/prod.keys"
    private const val FIRMWARE_DOCUMENT_ID = "root/nand/system/Contents/registered"
    private val TITLE_ID_REGEX = Regex("^[0-9A-Fa-f]{16}$")

    // Device saves (per console, not per user) live under an all-zero user ID in both layouts.
    private const val DEVICE_USER_ID = "00000000000000000000000000000000"
    private const val DEVICE_SAVE_DIR = "/user/save/0000000000000000/$DEVICE_USER_ID/"

    data class Source(val packageName: String, val label: String, val authority: String) {
        /**
         * Where the folder picker should open: the emulator's user folder. A root URI, not a
         * document URI - opening a document makes the picker call findDocumentPath(), which the
         * yuzu-family provider doesn't implement, so it silently fell back to the last folder.
         */
        val initialUri: Uri
            get() = DocumentsContract.buildRootUri(authority, PROVIDER_ROOT_ID)
    }

    data class SaveEntry(
        val titleId: String,
        val documentId: String,
        val lastModified: Long,
        val isDeviceSave: Boolean
    )

    data class Plan(
        val treeUri: Uri,
        val saves: List<SaveEntry>,
        val conflicts: Int,
        val importKeys: Boolean,
        val importFirmware: Boolean
    )

    data class Result(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val keysImported: Boolean,
        val firmwareImported: Boolean,
        val backupDir: File?
    )

    private data class Child(val id: String, val name: String, val isDir: Boolean, val lastModified: Long)

    /** Installed yuzu-family emulators that publish their user folder, other than this app. */
    fun findSources(context: Context): List<Source> {
        val packageManager = context.packageManager
        return packageManager
            .queryIntentContentProviders(Intent(DocumentsContract.PROVIDER_INTERFACE), 0)
            .mapNotNull { it.providerInfo }
            .filter { info ->
                info.packageName != context.packageName &&
                    info.name.endsWith(".features.DocumentProvider") &&
                    info.authority == "${info.packageName}.user"
            }
            .map { info ->
                Source(
                    info.packageName,
                    // Some forks put a slogan after the name ("citron-neo: The switch fell off...").
                    packageManager.getApplicationLabel(info.applicationInfo).toString()
                        .substringBefore(':').trim(),
                    info.authority
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** Null when the picked folder isn't the emulator's user folder itself. */
    fun scan(context: Context, source: Source, treeUri: Uri): Plan? {
        val pickedId = DocumentsContract.getTreeDocumentId(treeUri)
        if (treeUri.authority != source.authority || pickedId.trimEnd('/') != "root") {
            return null
        }

        // A title saved under several users/layouts: keep the most recently written copy. Device
        // saves are a separate save of the same title, so they're keyed apart.
        val saves = mutableMapOf<Pair<String, Boolean>, SaveEntry>()
        for (layoutDir in SAVE_LAYOUT_DIRS) {
            for (user in children(context, treeUri, layoutDir).filter { it.isDir }) {
                val isDevice = user.name.replace("-", "").all { it == '0' }
                for (title in children(context, treeUri, user.id)) {
                    if (!title.isDir || !TITLE_ID_REGEX.matches(title.name) ||
                        children(context, treeUri, title.id).isEmpty()
                    ) {
                        continue
                    }
                    val entry =
                        SaveEntry(title.name.uppercase(), title.id, title.lastModified, isDevice)
                    val key = entry.titleId to isDevice
                    val existing = saves[key]
                    if (existing == null || entry.lastModified > existing.lastModified) {
                        saves[key] = entry
                    }
                }
            }
        }

        val conflicts = saves.values.count { entry ->
            lemonSaveDir(entry)?.let { dir -> dir.list()?.isNotEmpty() == true } == true
        }
        val importKeys = !NativeLibrary.areKeysPresent() && exists(context, treeUri, KEYS_DOCUMENT_ID)
        val importFirmware = !NativeLibrary.isFirmwareAvailable() &&
            children(context, treeUri, FIRMWARE_DOCUMENT_ID).isNotEmpty()

        return Plan(
            treeUri,
            saves.values.sortedWith(compareBy({ it.titleId }, { it.isDeviceSave })),
            conflicts,
            importKeys,
            importFirmware
        )
    }

    fun migrate(
        context: Context,
        plan: Plan,
        replaceExisting: Boolean,
        progress: (done: Long, total: Long) -> Unit
    ): Result {
        var imported = 0
        var skipped = 0
        var failed = 0
        var backupDir: File? = null
        val total = plan.saves.size.toLong() + (if (plan.importFirmware) 1 else 0)
        var done = 0L

        for (save in plan.saves) {
            progress(done++, total)
            val target = lemonSaveDir(save)
            if (target == null) {
                failed++
                continue
            }
            try {
                if (target.list()?.isNotEmpty() == true) {
                    if (!replaceExisting) {
                        skipped++
                        continue
                    }
                    // Lemon's own save is replaced, never lost: keep a copy next to the user data.
                    val backupRoot = backupDir ?: File(
                        DirectoryInitialization.userDirectory,
                        "save_backups/" + LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    ).also { backupDir = it }
                    val backupName = if (save.isDeviceSave) "${save.titleId}-device" else save.titleId
                    target.copyRecursively(File(backupRoot, backupName), overwrite = true)
                    target.deleteRecursively()
                }
                target.mkdirs()
                copyTree(context, plan.treeUri, save.documentId, target)
                imported++
            } catch (e: Exception) {
                Log.error("[EmulatorMigration] ${save.titleId}: ${e.message}")
                failed++
            }
        }

        var keysImported = false
        if (plan.importKeys) {
            val keysUri = DocumentsContract.buildDocumentUriUsingTree(plan.treeUri, KEYS_DOCUMENT_ID)
            keysImported = NativeLibrary.installKeys(keysUri.toString(), "keys") == 0
        }

        var firmwareImported = false
        if (plan.importFirmware) {
            progress(done++, total)
            val firmwareDir = File(NativeConfig.getNandDir() + "/system/Contents/registered/")
            try {
                firmwareDir.deleteRecursively()
                firmwareDir.mkdirs()
                copyTree(context, plan.treeUri, FIRMWARE_DOCUMENT_ID, firmwareDir)
                GameHelper.reinitializeSystem()
                firmwareImported = NativeLibrary.isFirmwareAvailable()
            } catch (e: Exception) {
                Log.error("[EmulatorMigration] firmware: ${e.message}")
                firmwareDir.deleteRecursively()
            }
        }
        progress(total, total)

        return Result(imported, skipped, failed, keysImported, firmwareImported, backupDir)
    }

    /** Where Lemon keeps this save: the current user's for account saves, else the device's. */
    private fun lemonSaveDir(save: SaveEntry): File? {
        if (save.isDeviceSave) {
            return File(NativeConfig.getSaveDir() + DEVICE_SAVE_DIR + save.titleId)
        }
        val relative = NativeLibrary.getSavePath(BigInteger(save.titleId, 16).toString())
        return if (relative.isEmpty()) null else File(NativeConfig.getSaveDir() + relative)
    }

    private fun children(context: Context, treeUri: Uri, documentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            Child(
                                cursor.getString(0),
                                cursor.getString(1),
                                cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                                cursor.getLong(3)
                            )
                        )
                    }
                }
            } ?: emptyList()
        } catch (_: FileNotFoundException) {
            emptyList() // That folder doesn't exist in this emulator's user data.
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun exists(context: Context, treeUri: Uri, documentId: String): Boolean = try {
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null
        )?.use { it.moveToFirst() } == true
    } catch (_: Exception) {
        false
    }

    private fun copyTree(context: Context, treeUri: Uri, documentId: String, target: File) {
        for (child in children(context, treeUri, documentId)) {
            val destination = File(target, child.name)
            if (child.isDir) {
                destination.mkdirs()
                copyTree(context, treeUri, child.id, destination)
            } else {
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: throw FileNotFoundException(child.id)
            }
        }
    }
}
