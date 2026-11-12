// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lemon's cheat manager: the Atmosphere cheats of a game for the build it has installed, each
 * switched on or off, and new ones pasted or imported from a file. Opened from the game's page
 * before it runs, or from the in-game menu, where a change takes effect at once.
 */
class CheatsDialogFragment : DialogFragment() {
    private lateinit var game: Game
    private var buildId = ""

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private var addInput: EditText? = null

    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                importCheatFile(uri)
            }
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        game = BundleCompat.getParcelable(requireArguments(), ARG_GAME, Game::class.java)!!
        val context = requireContext()
        val padding = dp(20)

        status = TextView(context).apply {
            setText(R.string.cheats_reading)
            setPadding(0, 0, 0, dp(8))
        }
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(8), padding, 0)
            addView(status)
            addView(list)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.cheats_title, game.title))
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.cheats_add, null)
            .create()
        dialog.setOnShowListener {
            // Adding opens a second dialog; this one stays open to show the result.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                isEnabled = false
                setOnClickListener { showAddDialog() }
            }
        }
        loadBuildId()
        return dialog
    }

    private fun loadBuildId() {
        lifecycleScope.launch {
            // Before the game runs this reads its executable, which takes a moment.
            buildId = withContext(Dispatchers.IO) {
                NativeLibrary.getCheatBuildId(game.path, game.programId)
            }
            if (buildId.isEmpty()) {
                status.setText(R.string.cheats_no_build_id)
                return@launch
            }
            status.text = getString(R.string.cheats_build_id, buildId)
            (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = true
            refreshList()
        }
    }

    private fun refreshList() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                NativeLibrary.getCheats(game.programId, buildId)
            }.map { CheatRow.parse(it) }
            list.removeAllViews()
            if (rows.isEmpty()) {
                list.addView(TextView(requireContext()).apply {
                    text = getString(R.string.cheats_empty, buildId)
                    setPadding(0, dp(8), 0, dp(8))
                })
                return@launch
            }
            val showFolders = rows.map { it.folder }.distinct().size > 1
            var lastFolder: String? = null
            for (row in rows) {
                if (showFolders && row.folder != lastFolder) {
                    list.addView(folderHeader(row.folder))
                    lastFolder = row.folder
                }
                list.addView(cheatSwitch(row))
            }
        }
    }

    private fun folderHeader(folder: String) = TextView(requireContext()).apply {
        text = folder.ifEmpty { getString(R.string.cheats_loose_files) }
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun cheatSwitch(row: CheatRow) = MaterialSwitch(requireContext()).apply {
        text = if (row.master) getString(R.string.cheats_master, row.name) else row.name
        isChecked = row.enabled
        // The master code runs whenever its cheats do, and loose cheat_*.txt files have no place to
        // keep a switch.
        isEnabled = !row.master && row.folder.isNotEmpty()
        minHeight = dp(48)
        setOnCheckedChangeListener { view, checked ->
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    NativeLibrary.setCheatEnabled(game.programId, row.folder, row.name, checked)
                }
                if (!saved) {
                    view.isChecked = !checked
                    Toast.makeText(context, R.string.cheats_toggle_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showAddDialog() {
        val context = requireContext()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            setHint(R.string.cheats_paste_hint)
        }
        addInput = input
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(context).apply {
                text = getString(R.string.cheats_add_description, buildId)
                setPadding(0, 0, 0, dp(8))
            })
            addView(input)
        }
        val addDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.cheats_add)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(R.string.cheats_add_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.cheats_import_file, null)
            .create()
        addDialog.setOnShowListener {
            addDialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                importFile.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
            }
            addDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                addCheats(input.text.toString(), addDialog)
            }
        }
        addDialog.setOnDismissListener { addInput = null }
        addDialog.show()
    }

    private fun addCheats(text: String, addDialog: AlertDialog) {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                NativeLibrary.addCheats(game.programId, buildId, text)
            }
            if (count == 0) {
                Toast.makeText(requireContext(), R.string.cheats_none_found, Toast.LENGTH_LONG)
                    .show()
                return@launch
            }
            Toast.makeText(
                requireContext(),
                resources.getQuantityString(R.plurals.cheats_added, count, count),
                Toast.LENGTH_SHORT
            ).show()
            addDialog.dismiss()
            refreshList()
        }
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
                Toast.makeText(requireContext(), R.string.cheats_none_found, Toast.LENGTH_LONG)
                    .show()
                return@launch
            }
            addInput?.setText(text)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
