// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.BundleCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButtonRow
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonIconButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonColors
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonSwitchRow
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonType
import dev.ironlemon.ironlemon_emulator.utils.CheatDatabase

/**
 * The cheat manager: the Atmosphère cheats of the game's build, one switch each, grouped by the mod
 * folder they are in. Cheats can be pasted or imported from a file, or downloaded from
 * switch-cheats-db; a folder can be deleted with its cheats. While the game runs, every change is
 * loaded into it at once.
 */
class CheatsDialogFragment : IronLemonComposeDialogFragment() {
    override val widthFraction = 0.84f
    override val heightFraction = 0.88f

    private lateinit var game: Game

    private sealed interface Page {
        data object List : Page
        data object Add : Page
        data object ConfirmDownload : Page
        data class ConfirmDeleteFolder(val folder: String) : Page
    }

    private var page by mutableStateOf<Page>(Page.List)
    private var buildId by mutableStateOf("")
    private var status by mutableStateOf<String?>(null)
    private var rows by mutableStateOf<List<CheatRow>?>(null)
    private var downloading by mutableStateOf(false)
    private var addText by mutableStateOf("")

    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                importCheatFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        game = BundleCompat.getParcelable(requireArguments(), ARG_GAME, Game::class.java)!!
        status = getString(R.string.cheats_reading)
        loadBuildId()
    }

    @Composable
    override fun DialogContent() {
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(IronLemonColors.Background)
                .border(1.dp, IronLemonColors.gradient(0.5f), shape)
                .padding(28.dp)
        ) {
            when (val current = page) {
                Page.List -> ListPage()
                Page.Add -> AddPage()
                Page.ConfirmDownload -> ConfirmPage(
                    title = stringResource(R.string.cheats_download),
                    message = stringResource(R.string.cheats_download_description, buildId),
                    confirm = stringResource(R.string.cheats_download_confirm)
                ) {
                    page = Page.List
                    downloadCheats()
                }

                is Page.ConfirmDeleteFolder -> ConfirmPage(
                    title = stringResource(R.string.cheats_delete_folder),
                    message = stringResource(R.string.cheats_delete_folder_confirm, current.folder),
                    confirm = stringResource(R.string.cheats_delete_folder)
                ) {
                    page = Page.List
                    deleteFolder(current.folder)
                }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.ListPage() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                stringResource(R.string.cheats_title, game.title),
                style = IronLemonType.Heading,
                modifier = Modifier.weight(1f)
            )
            IronLemonIconButton(
                iconRes = R.drawable.ic_close,
                description = stringResource(R.string.close),
                onClick = ::dismiss
            )
        }
        status?.let {
            Spacer(Modifier.height(6.dp))
            BasicText(it, style = IronLemonType.Body)
        }
        Spacer(Modifier.height(16.dp))
        val list = rows
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (list != null && list.isEmpty()) {
                item { IronLemonDialogText(stringResource(R.string.cheats_empty, buildId)) }
            }
            val groups = list.orEmpty().groupBy { it.folder }
            groups.forEach { (folder, cheats) ->
                item(key = "folder:$folder") { FolderHeader(folder) }
                items(cheats, key = { "${it.folder}/${it.name}" }) { row -> CheatSwitch(row) }
            }
        }
        Spacer(Modifier.height(16.dp))
        val ready = buildId.isNotEmpty()
        IronLemonButtonRow {
            IronLemonButton(
                text = stringResource(R.string.cheats_download),
                iconRes = R.drawable.ic_download,
                height = 48.dp,
                onClick = { if (ready && !downloading) page = Page.ConfirmDownload }
            )
            IronLemonButton(
                text = stringResource(R.string.cheats_add),
                iconRes = R.drawable.ic_add,
                height = 48.dp,
                onClick = { if (ready) page = Page.Add }
            )
        }
    }

    /** The folder's name and, for a mod folder, a button that deletes it with its cheats. */
    @Composable
    private fun FolderHeader(folder: String) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                folder.ifEmpty { stringResource(R.string.cheats_loose_files) },
                style = IronLemonType.Label,
                modifier = Modifier.weight(1f)
            )
            if (folder.isNotEmpty()) {
                IronLemonButton(
                    text = stringResource(R.string.cheats_delete_folder),
                    iconRes = R.drawable.ic_delete,
                    height = 40.dp,
                    onClick = { page = Page.ConfirmDeleteFolder(folder) }
                )
            }
        }
    }

    @Composable
    private fun CheatSwitch(row: CheatRow) {
        val label = if (row.master) stringResource(R.string.cheats_master, row.name) else row.name
        // The master code runs whenever its cheats do, and loose cheat_*.txt files have no place to
        // keep a switch.
        val switchable = !row.master && row.folder.isNotEmpty()
        IronLemonSwitchRow(label, row.enabled) { checked ->
            if (switchable) {
                setEnabled(row, checked)
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.AddPage() {
        BasicText(stringResource(R.string.cheats_add), style = IronLemonType.Heading)
        Spacer(Modifier.height(8.dp))
        BasicText(stringResource(R.string.cheats_add_description, buildId), style = IronLemonType.Body)
        Spacer(Modifier.height(12.dp))
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(shape)
                .background(IronLemonColors.Surface)
                .border(1.dp, IronLemonColors.Outline, shape)
                .padding(16.dp)
        ) {
            if (addText.isEmpty()) {
                BasicText(stringResource(R.string.cheats_paste_hint), style = IronLemonType.Body)
            }
            BasicTextField(
                value = addText,
                onValueChange = { addText = it },
                textStyle = IronLemonType.Body.copy(
                    color = IronLemonColors.Text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                cursorBrush = SolidColor(IronLemonColors.Lemon),
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(16.dp))
        IronLemonButtonRow {
            IronLemonButton(
                text = stringResource(R.string.cheats_import_file),
                iconRes = R.drawable.ic_import,
                height = 48.dp,
                onClick = {
                    importFile.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                }
            )
            IronLemonButton(
                text = stringResource(android.R.string.cancel),
                height = 48.dp,
                onClick = {
                    addText = ""
                    page = Page.List
                }
            )
            IronLemonButton(
                text = stringResource(R.string.cheats_add_confirm),
                primary = true,
                height = 48.dp,
                onClick = { addCheats(addText) }
            )
        }
    }

    @Composable
    private fun ConfirmPage(title: String, message: String, confirm: String, onConfirm: () -> Unit) {
        BasicText(title, style = IronLemonType.Heading)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.heightIn(max = 520.dp)) { IronLemonDialogText(message) }
        Spacer(Modifier.height(24.dp))
        IronLemonButtonRow {
            IronLemonButton(
                text = stringResource(android.R.string.cancel),
                height = 48.dp,
                onClick = { page = Page.List }
            )
            IronLemonButton(text = confirm, primary = true, height = 48.dp, onClick = onConfirm)
        }
    }

    private fun loadBuildId() {
        lifecycleScope.launch {
            // Before the game runs this reads its executable, which takes a moment.
            buildId = withContext(Dispatchers.IO) {
                NativeLibrary.getCheatBuildId(game.path, game.programId)
            }
            if (buildId.isEmpty()) {
                status = getString(R.string.cheats_no_build_id)
                return@launch
            }
            status = getString(R.string.cheats_build_id, buildId)
            refreshList()
        }
    }

    private fun refreshList() {
        lifecycleScope.launch {
            rows = withContext(Dispatchers.IO) {
                NativeLibrary.getCheats(game.programId, buildId)
            }.map { CheatRow.parse(it) }
        }
    }

    private fun setEnabled(row: CheatRow, checked: Boolean) {
        // Shown at once; put back if it can't be saved.
        rows = rows?.map { if (it == row) it.copy(enabled = checked) else it }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                NativeLibrary.setCheatEnabled(game.programId, buildId, row.folder, row.name, checked)
            }
            if (result != 0) {
                rows = rows?.map {
                    if (it.folder == row.folder && it.name == row.name) it.copy(enabled = !checked) else it
                }
                toast(if (result == 2) R.string.cheats_too_many else R.string.cheats_toggle_failed, true)
            }
        }
    }

    private fun deleteFolder(folder: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                NativeLibrary.removeMod(game.programId, folder)
                NativeLibrary.reloadCheats(game.programId)
            }
            refreshList()
        }
    }

    private fun addCheats(text: String) {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                NativeLibrary.addCheats(game.programId, buildId, text, "")
            }
            if (count <= 0) {
                toast(if (count < 0) R.string.cheats_save_failed else R.string.cheats_none_found, true)
                return@launch
            }
            Toast.makeText(
                requireContext(),
                resources.getQuantityString(R.plurals.cheats_added, count, count),
                Toast.LENGTH_SHORT
            ).show()
            addText = ""
            page = Page.List
            refreshList()
        }
    }

    private fun downloadCheats() {
        downloading = true
        status = getString(R.string.cheats_downloading)
        val titleId = "%016X".format(game.programId.toLongOrNull() ?: 0L)
        lifecycleScope.launch {
            val results = CheatDatabase.Kind.entries.map { kind ->
                kind to CheatDatabase.fetch(kind, titleId, buildId)
            }
            var added = 0
            var saveFailed = false
            for ((kind, result) in results) {
                if (result !is CheatDatabase.Result.Found) {
                    continue
                }
                val count = withContext(Dispatchers.IO) {
                    NativeLibrary.addCheats(game.programId, buildId, result.text, folderFor(kind))
                }
                if (count < 0) {
                    saveFailed = true
                } else {
                    added += count
                }
            }
            downloading = false
            if (!isAdded) {
                return@launch
            }
            val outcomes = results.map { it.second }
            val otherBuilds = outcomes.filterIsInstance<CheatDatabase.Result.OtherVersions>()
                .maxOfOrNull { it.count } ?: 0
            val message = when {
                added > 0 -> resources.getQuantityString(R.plurals.cheats_downloaded, added, added)
                saveFailed -> getString(R.string.cheats_save_failed)
                outcomes.any { it is CheatDatabase.Result.Failed } ->
                    getString(R.string.cheats_download_failed)
                outcomes.all { it is CheatDatabase.Result.NotFound } ->
                    getString(R.string.cheats_download_not_found)
                else -> resources.getQuantityString(
                    R.plurals.cheats_download_other_builds, otherBuilds, buildId, otherBuilds
                )
            }
            // Kept in the dialog: a toast alone was easy to miss.
            status = if (added > 0) getString(R.string.cheats_build_id, buildId) else message
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            if (added > 0) {
                refreshList()
            }
        }
    }

    /**
     * Each kind of downloaded cheats has its own folder, replaced by the next download. These are
     * folder names on disk (the same in every language), not text of the app.
     */
    private fun folderFor(kind: CheatDatabase.Kind) = when (kind) {
        CheatDatabase.Kind.GAMEPLAY -> "Downloaded cheats"
        CheatDatabase.Kind.GRAPHICS -> "Downloaded cheats (60 FPS, graphics)"
    }

    private fun importCheatFile(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                        // Cheat files are a few KB; don't read something huge picked by mistake.
                        val buffer = ByteArray(MAX_IMPORT_BYTES)
                        var size = 0
                        while (size < buffer.size) {
                            val read = stream.read(buffer, size, buffer.size - size)
                            if (read < 0) break
                            size += read
                        }
                        String(buffer, 0, size, Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                toast(R.string.cheats_none_found, true)
                return@launch
            }
            addText = text
        }
    }

    private fun toast(message: Int, long: Boolean = false) = Toast.makeText(
        requireContext(),
        message,
        if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
    ).show()

    private data class CheatRow(
        val folder: String,
        val name: String,
        val enabled: Boolean,
        val master: Boolean
    ) {
        companion object {
            // As NativeLibrary.getCheats returns them: folder, name, enabled, master.
            fun parse(row: String): CheatRow {
                val fields = row.split('\u001F')
                return CheatRow(
                    folder = fields.getOrElse(0) { "" },
                    name = fields.getOrElse(1) { "" },
                    enabled = fields.getOrElse(2) { "0" } == "1",
                    master = fields.getOrElse(3) { "0" } == "1"
                )
            }
        }
    }

    companion object {
        const val TAG = "CheatsDialogFragment"
        private const val ARG_GAME = "game"
        private const val MAX_IMPORT_BYTES = 256 * 1024

        fun newInstance(game: Game) = CheatsDialogFragment().apply {
            arguments = Bundle().apply { putParcelable(ARG_GAME, game) }
        }
    }
}
