// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.features

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.utils.GameHelper
import dev.ironlemon.ironlemon_emulator.utils.GameMetadata
import dev.ironlemon.ironlemon_emulator.utils.Log
import java.io.File
import java.io.FileNotFoundException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Tells frontends (launchers) the name and icon of a game in Iron Lemon's own library, which they cannot
 * read themselves: both live in the game's encrypted NCAs, and only the emulator has the user's keys.
 *
 * Read only, and only about one game at a time, asked by its document id or its program id:
 * `content://<package>.library/title?doc_id=…` or `…/title?program_id=<16 hex digits>` answer one
 * row or none, `content://<package>.library/icon/<PROGRAM_ID>` opens the icon. It never lists the
 * library, never opens a file the app was not given, and never exposes keys, settings or saves.
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
        val cursor = MatrixCursor(COLUMNS)
        try {
            if (uri.pathSegments == listOf(PATH_TITLE)) {
                val params = queryParameters(uri)
                val docId = params[PARAM_DOC_ID]
                val game = if (docId != null) {
                    findByDocumentId(context, docId)
                } else {
                    params[PARAM_PROGRAM_ID]?.let { findByProgramId(context, Uri.decode(it)) }
                }
                if (game != null) {
                    val programId = hexProgramId(game)
                    cursor.addRow(
                        arrayOf(
                            programId,
                            game.title.ifEmpty { null },
                            game.developer.ifEmpty { null },
                            game.version.ifEmpty { null },
                            programId?.let { iconUri(context, it).toString() },
                            updatedAt(context, game)
                        )
                    )
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
        if (mode != "r") {
            throw FileNotFoundException("Read only")
        }
        val segments = uri.pathSegments
        val asked = segments.getOrNull(1)
        if (segments.size != 2 || segments[0] != PATH_ICON || asked == null ||
            !PROGRAM_ID_PATTERN.matches(asked)
        ) {
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
            segments == listOf(PATH_TITLE) -> "vnd.android.cursor.item/vnd.ironlemon.title"
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

    companion object {
        private const val PATH_TITLE = "title"
        private const val PATH_ICON = "icon"
        private const val PARAM_DOC_ID = "doc_id"
        private const val PARAM_PROGRAM_ID = "program_id"
        private const val ICON_DIR = "library_icons"
        private val PROGRAM_ID_PATTERN = Regex("^[0-9A-Fa-f]{16}$")

        val COLUMNS = arrayOf("program_id", "name", "developer", "version", "icon_uri", "updated_at")

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
