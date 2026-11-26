// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.features

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import dev.lemon.lemon_emu.LemonApplication
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.utils.DirectoryInitialization
import dev.lemon.lemon_emu.utils.FileUtil
import dev.lemon.lemon_emu.utils.GameHelper
import dev.lemon.lemon_emu.utils.GameMetadata
import dev.lemon.lemon_emu.utils.Log
import dev.lemon.lemon_emu.model.TaskState
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlinx.serialization.json.Json

/**
 * Tells frontends (launchers) the name and icon of a game in Lemon's own library, which they cannot
 * read themselves: both live in the game's encrypted NCAs, and only Lemon has the user's keys.
 *
 * Read only, and only about one game at a time, asked by its document id or its program id
 * (`?doc_id=…` or `?program_id=<16 hex digits>`):
 * - `title`: one row with the name, icon URI and play time, or none;
 *   `icon/<PROGRAM_ID>` opens the icon.
 * - `saves`: whether the game has save data; `save_zip/<PROGRAM_ID>` opens it as a ZIP, but only
 *   for an app Lemon granted it to after the user agreed (see ExternalGameActivity).
 * - `screenshots`: the game's screenshots; `screenshot/<name>` opens one.
 * It never lists the library, never opens a file Lemon was not given, and never exposes keys or
 * settings.
 */
class LibraryProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val context = requireNotNull(context)
        LemonApplication.awaitStarted()
        val path = uri.pathSegments.singleOrNull()
        val cursor = MatrixCursor(
            when (path) {
                PATH_SAVES -> SAVE_COLUMNS
                PATH_SCREENSHOTS -> SCREENSHOT_COLUMNS
                else -> COLUMNS
            }
        )
        try {
            val game = if (path in QUERY_PATHS) gameOf(context, uri) else null
            if (game != null) {
                when (path) {
                    PATH_TITLE -> addTitleRow(context, cursor, game)
                    PATH_SAVES -> addSaveRow(context, cursor, game)
                    PATH_SCREENSHOTS -> addScreenshotRows(context, cursor, game)
                }
            }
        } catch (e: Exception) {
            // A frontend only ever gets a row or an empty cursor.
            Log.warning("[LibraryProvider] query failed: ${e.javaClass.simpleName}")
        }
        cursor.setNotificationUri(context.contentResolver, titleUri(context))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = requireNotNull(context)
        if (!LemonApplication.awaitStarted()) throw FileNotFoundException("Not ready")
        if (mode != "r") {
            throw FileNotFoundException("Read only")
        }
        val segments = uri.pathSegments
        val asked = segments.getOrNull(1)
        if (segments.size != 2 || asked == null) {
            throw FileNotFoundException("Unknown")
        }
        when (segments[0]) {
            PATH_SAVE_ZIP -> return openSaveZip(context, asked)
            PATH_SCREENSHOT -> return openScreenshot(context, asked)
        }
        if (segments[0] != PATH_ICON || !PROGRAM_ID_PATTERN.matches(asked)) {
            throw FileNotFoundException("Not an icon")
        }
        val programId = asked.uppercase()
        val game = findByProgramId(context, programId)
            ?: throw FileNotFoundException("Not in the library")
        val file = cachedIcon(context, game, programId)
            ?: throw FileNotFoundException("No icon")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        val context = context ?: return null
        val segments = uri.pathSegments
        return when {
            segments == listOf(PATH_TITLE) -> "vnd.android.cursor.item/vnd.lemon.title"
            segments == listOf(PATH_SAVES) -> "vnd.android.cursor.item/vnd.lemon.saves"
            segments == listOf(PATH_SCREENSHOTS) -> "vnd.android.cursor.dir/vnd.lemon.screenshot"
            segments.size == 2 && segments[0] == PATH_SAVE_ZIP -> "application/zip"
            segments.size == 2 && segments[0] == PATH_SCREENSHOT -> "image/png"
            segments.size == 2 && segments[0] == PATH_ICON &&
                PROGRAM_ID_PATTERN.matches(segments[1]) -> {
                val file = iconFile(context, segments[1].uppercase())
                if (file.isFile) imageType(file) else "image/jpeg"
            }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    /**
     * The icon from the cache, taken from the game file the first time (and again when the game
     * changed in the library). Not while a game runs or the library is being scanned: both use the
     * same native metadata, and a scan frees what the read would use.
     */
    private fun cachedIcon(context: Context, game: Game, programId: String): File? {
        val file = iconFile(context, programId)
        val updated = updatedAt(context, game)
        if (file.isFile && file.length() > 0 && file.lastModified() >= updated) {
            return file
        }
        val busy = NativeLibrary.isRunning() || GameHelper.scanProgress.value != null
        if (busy) {
            return file.takeIf { it.isFile && it.length() > 0 }
        }
        val data = synchronized(GameHelper) {
            NativeLibrary.reloadKeys()
            GameMetadata.getIcon(game.path)
        }
        if (data.isEmpty()) {
            file.delete()
            return null
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(data)
        if (!temp.renameTo(file)) {
            temp.delete()
            return null
        }
        return file
    }

    private fun addTitleRow(context: Context, cursor: MatrixCursor, game: Game) {
        val programId = hexProgramId(game)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val playSeconds = runCatching { NativeLibrary.playTimeManagerGetPlayTime(game.programId) }
            .getOrNull()
        val lastPlayed = preferences.getLong(game.keyLastPlayedTime, 0L)
        val sessions = preferences.getInt(game.keySessionCount, 0)
        cursor.addRow(
            arrayOf(
                programId,
                game.title.ifEmpty { null },
                game.developer.ifEmpty { null },
                game.version.ifEmpty { null },
                programId?.let { iconUri(context, it).toString() },
                updatedAt(context, game),
                playSeconds?.takeIf { it > 0 },
                lastPlayed.takeIf { it > 0 },
                sessions.takeIf { it > 0 }
            )
        )
    }

    /** Whether the game has save data, how big and how recent. Reading it needs a grant. */
    private fun addSaveRow(context: Context, cursor: MatrixCursor, game: Game) {
        val programId = hexProgramId(game) ?: return
        val folder = saveFolder(game)
        val files = folder?.walkTopDown()?.filter { it.isFile }?.toList().orEmpty()
        val hasSaves = files.isNotEmpty()
        cursor.addRow(
            arrayOf(
                if (hasSaves) 1 else 0,
                files.sumOf { it.length() },
                files.maxOfOrNull { it.lastModified() },
                if (hasSaves) saveZipUri(context, programId).toString() else null
            )
        )
    }

    /** The game's screenshots, newest first. */
    private fun addScreenshotRows(context: Context, cursor: MatrixCursor, game: Game) {
        val programId = hexProgramId(game) ?: return
        screenshotsOf(programId).sortedByDescending { it.lastModified() }.forEach { file ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            cursor.addRow(
                arrayOf(
                    screenshotUri(context, file.name).toString(),
                    file.lastModified(),
                    bounds.outWidth.takeIf { it > 0 },
                    bounds.outHeight.takeIf { it > 0 }
                )
            )
        }
    }

    /**
     * The game's save data as a ZIP in the format Lemon's own export writes (the save folder, named
     * by program id, at the root), made when asked. Only for a caller Lemon granted it to after the
     * user agreed (ExternalGameActivity's EXPORT_SAVES); the manifest guards this path.
     */
    private fun openSaveZip(context: Context, asked: String): ParcelFileDescriptor {
        if (!PROGRAM_ID_PATTERN.matches(asked)) throw FileNotFoundException("Not a game")
        val game = findByProgramId(context, asked) ?: throw FileNotFoundException("Not in the library")
        val folder = saveFolder(game)?.takeIf { dir -> dir.walkTopDown().any { it.isFile } }
            ?: throw FileNotFoundException("No save data")
        val zip = File(File(context.cacheDir, SAVE_ZIP_DIR), "${asked.uppercase()}.zip")
        zip.parentFile?.mkdirs()
        val state = BufferedOutputStream(FileOutputStream(zip)).let { output ->
            FileUtil.zipFromInternalStorage(
                folder,
                folder.path.replaceAfterLast("/", ""),
                output,
                compression = false
            )
        }
        if (state != TaskState.Completed) {
            zip.delete()
            throw FileNotFoundException("Could not pack the save")
        }
        return ParcelFileDescriptor.open(zip, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun openScreenshot(context: Context, name: String): ParcelFileDescriptor {
        val match = SCREENSHOT_PATTERN.matchEntire(name) ?: throw FileNotFoundException("Not a screenshot")
        findByProgramId(context, match.groupValues[1]) ?: throw FileNotFoundException("Not in the library")
        val file = screenshotsOf(match.groupValues[1].uppercase()).firstOrNull { it.name == name }
            ?: throw FileNotFoundException("No such screenshot")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    companion object {
        private const val PATH_TITLE = "title"
        private const val PATH_ICON = "icon"
        private const val PATH_SAVES = "saves"
        private const val PATH_SAVE_ZIP = "save_zip"
        private const val PATH_SCREENSHOTS = "screenshots"
        private const val PATH_SCREENSHOT = "screenshot"
        private const val SAVE_ZIP_DIR = "library_saves"
        private val QUERY_PATHS = setOf(PATH_TITLE, PATH_SAVES, PATH_SCREENSHOTS)
        private val SCREENSHOT_PATTERN = Regex("^([0-9a-f]{16})_[0-9_-]+\\.png$")

        val SAVE_COLUMNS = arrayOf("has_saves", "size_bytes", "updated_at", "zip_uri")
        val SCREENSHOT_COLUMNS = arrayOf("uri", "taken_at", "width", "height")

        /** The game a query names, by doc_id (first) or program_id. */
        private fun gameOf(context: Context, uri: Uri): Game? {
            val params = queryParameters(uri)
            val docId = params[PARAM_DOC_ID]
            return if (docId != null) {
                findByDocumentId(context, docId)
            } else {
                params[PARAM_PROGRAM_ID]?.let { findByProgramId(context, Uri.decode(it)) }
            }
        }

        /** The game from an intent's extras (doc_id or program_id, plain text). */
        fun gameFromExtras(context: Context, docId: String?, programId: String?): Game? = when {
            !docId.isNullOrEmpty() -> findByDocumentId(context, Uri.encode(docId))
            !programId.isNullOrEmpty() -> findByProgramId(context, programId)
            else -> null
        }

        /** The game of the library whose program id is [programId] (16 hex digits). */
        fun gameWithProgramId(context: Context, programId: String): Game? =
            findByProgramId(context, programId)

        fun saveZipUri(context: Context, programId: String): Uri =
            Uri.parse("content://${authority(context)}/$PATH_SAVE_ZIP/${programId.uppercase()}")

        private fun screenshotUri(context: Context, name: String): Uri =
            Uri.parse("content://${authority(context)}/$PATH_SCREENSHOT/$name")

        /** The game's save folder, or null when the emulator cannot tell where it is. */
        fun saveFolder(game: Game): File? =
            runCatching { File(game.saveDir) }.getOrNull()?.takeIf { it.isDirectory }

        private fun screenshotsOf(programId: String): List<File> {
            val directory = File(DirectoryInitialization.userDirectory ?: return emptyList(), "screenshots")
            val prefix = "${programId.lowercase()}_"
            return directory.listFiles()?.filter {
                it.isFile && it.name.startsWith(prefix) && SCREENSHOT_PATTERN.matches(it.name)
            }.orEmpty()
        }
        private const val PARAM_DOC_ID = "doc_id"
        private const val PARAM_PROGRAM_ID = "program_id"
        private const val ICON_DIR = "library_icons"
        private val PROGRAM_ID_PATTERN = Regex("^[0-9A-Fa-f]{16}$")

        val COLUMNS = arrayOf(
            "program_id", "name", "developer", "version", "icon_uri", "updated_at",
            "play_seconds", "last_played", "sessions"
        )

        private fun authority(context: Context) = "${context.packageName}.library"

        fun titleUri(context: Context): Uri =
            Uri.parse("content://${authority(context)}/$PATH_TITLE")

        private fun iconUri(context: Context, programId: String): Uri =
            Uri.parse("content://${authority(context)}/$PATH_ICON/$programId")

        /** Tells frontends that listen that the library changed (after a scan). */
        fun notifyLibraryChanged(context: Context) {
            runCatching { context.contentResolver.notifyChange(titleUri(context), null) }
        }

        private fun iconFile(context: Context, programId: String) =
            File(File(context.cacheDir, ICON_DIR), programId)

        private fun imageType(file: File): String {
            val head = ByteArray(4)
            val read = file.inputStream().use { it.read(head) }
            val png = read == 4 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
                head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()
            return if (png) "image/png" else "image/jpeg"
        }

        /**
         * The query's values still encoded: a '+' may be a '+' of the file name or a space from a
         * caller that encodes like a form, so [findByDocumentId] decodes them both ways.
         */
        private fun queryParameters(uri: Uri): Map<String, String> {
            val query = uri.encodedQuery ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            for (pair in query.split('&')) {
                val separator = pair.indexOf('=')
                if (separator <= 0) continue
                val key = Uri.decode(pair.substring(0, separator))
                if (key !in result) {
                    result[key] = pair.substring(separator + 1)
                }
            }
            return result
        }

        /** The library as the last scan left it (the app may not have opened its game list yet). */
        private fun library(context: Context): List<Game> {
            val stored = PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(GameHelper.KEY_GAMES, emptySet()) ?: emptySet()
            return stored.mapNotNull { runCatching { Json.decodeFromString<Game>(it) }.getOrNull() }
        }

        private fun documentIdOf(game: Game): String? =
            runCatching { DocumentsContract.getDocumentId(Uri.parse(game.path)) }.getOrNull()

        private fun findByDocumentId(context: Context, encodedDocId: String): Game? {
            if (encodedDocId.isEmpty()) return null
            val byId = library(context).associateBy { documentIdOf(it) }
            return byId[Uri.decode(encodedDocId)]
                ?: byId[Uri.decode(encodedDocId.replace("+", "%20"))]
        }

        private fun findByProgramId(context: Context, programId: String): Game? {
            if (!PROGRAM_ID_PATTERN.matches(programId)) return null
            val wanted = programId.uppercase()
            return library(context).firstOrNull { hexProgramId(it) == wanted }
        }

        /** 16 upper-case hex digits, or null for a game without one (homebrew named by its file). */
        private fun hexProgramId(game: Game): String? {
            val id = game.programId.toLongOrNull() ?: return null
            return if (id == 0L) null else String.format("%016X", id)
        }

        private fun updatedAt(context: Context, game: Game): Long {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val updated = preferences.getLong(game.keyLibraryUpdatedAt, 0L)
            return if (updated != 0L) updated else preferences.getLong(game.keyAddedToLibraryTime, 0L)
        }
    }
}
