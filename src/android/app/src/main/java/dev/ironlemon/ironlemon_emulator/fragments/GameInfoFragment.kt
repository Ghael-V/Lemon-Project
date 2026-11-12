// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.fragment.navArgs
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.GameVerificationResult
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonActionCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonPage
import dev.ironlemon.ironlemon_emulator.utils.GameMetadata

/** A game's path, program ID, developer and version (tap one to copy it), and a content check. */
class GameInfoFragment : IronLemonComposeFragment() {
    private val args by navArgs<GameInfoFragmentArgs>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Check for an up-to-date version string
        args.game.version = GameMetadata.getVersion(args.game.path, true)
    }

    @Composable
    override fun ScreenContent() {
        val game = args.game
        val pathString = Uri.parse(game.path).path ?: ""
        val fields = buildList {
            add(R.string.path to pathString)
            add(R.string.program_id to game.programIdHex)
            if (game.developer.isNotEmpty()) {
                add(R.string.developer to game.developer)
            }
            add(R.string.version to game.version)
        }
        IronLemonPage(title = game.title, onBack = ::goBack) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                fields.forEach { (label, value) ->
                    val name = stringResource(label)
                    IronLemonActionCard(
                        title = value.ifEmpty { "-" },
                        description = name,
                        onClick = { copyToClipboard(name, value) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IronLemonButton(
                        text = stringResource(R.string.copy_details),
                        onClick = { copyDetails(pathString) }
                    )
                    IronLemonButton(
                        text = stringResource(R.string.verify_integrity),
                        iconRes = R.drawable.ic_check_circle,
                        primary = true,
                        onClick = ::verifyIntegrity
                    )
                }
            }
        }
    }

    private fun copyDetails(pathString: String) {
        val game = args.game
        val details = """
            ${game.title}
            ${getString(R.string.path)} - $pathString
            ${getString(R.string.program_id)} - ${game.programIdHex}
            ${getString(R.string.developer)} - ${game.developer}
            ${getString(R.string.version)} - ${game.version}
        """.trimIndent()
        copyToClipboard(game.title, details)
    }

    private fun verifyIntegrity() {
        ProgressDialogFragment.newInstance(
            requireActivity(),
            R.string.verifying,
            true
        ) { progressCallback, _ ->
            val result = GameVerificationResult.from(
                NativeLibrary.verifyGameContents(
                    args.game.path,
                    progressCallback
                )
            )
            return@newInstance when (result) {
                GameVerificationResult.Success ->
                    MessageDialogFragment.newInstance(
                        titleId = R.string.verify_success,
                        descriptionId = R.string.operation_completed_successfully
                    )

                GameVerificationResult.Failed ->
                    MessageDialogFragment.newInstance(
                        titleId = R.string.verify_failure,
                        descriptionId = R.string.verify_failure_description
                    )

                GameVerificationResult.NotImplemented ->
                    MessageDialogFragment.newInstance(
                        titleId = R.string.verify_no_result,
                        descriptionId = R.string.verify_no_result_description
                    )
            }
        }.show(parentFragmentManager, ProgressDialogFragment.TAG)
    }

    private fun copyToClipboard(label: String, body: String) {
        val clipBoard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, body)
        clipBoard.setPrimaryClip(clip)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(
                requireContext(),
                R.string.copied_to_clipboard,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
