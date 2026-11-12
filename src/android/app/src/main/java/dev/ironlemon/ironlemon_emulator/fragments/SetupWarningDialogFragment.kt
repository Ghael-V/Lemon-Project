// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText

/** First-run setup: a step was left undone; skip it anyway or go back to it. */
class SetupWarningDialogFragment : IronLemonComposeDialogFragment() {
    private var titleId: Int = 0
    private var descriptionId: Int = 0
    private var helpLinkId: Int = 0
    private var page: Int = 0

    private lateinit var setupFragment: SetupFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titleId = requireArguments().getInt(TITLE)
        descriptionId = requireArguments().getInt(DESCRIPTION)
        helpLinkId = requireArguments().getInt(HELP_LINK)
        page = requireArguments().getInt(PAGE)

        setupFragment = requireParentFragment() as SetupFragment
    }

    @Composable
    override fun DialogContent() {
        IronLemonDialogCard(
            title = if (titleId != 0) stringResource(titleId) else null,
            buttons = {
                if (helpLinkId != 0) {
                    IronLemonButton(
                        text = stringResource(R.string.warning_help),
                        height = 44.dp,
                        onClick = {
                            val helpLink = getString(R.string.install_prod_keys_warning_help)
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(helpLink)))
                        }
                    )
                }
                IronLemonButton(
                    text = stringResource(R.string.warning_cancel),
                    height = 44.dp,
                    onClick = ::dismiss
                )
                IronLemonButton(
                    text = stringResource(R.string.warning_skip),
                    primary = true,
                    height = 44.dp,
                    onClick = {
                        setupFragment.pageForward()
                        setupFragment.setPageWarned(page)
                        dismiss()
                    }
                )
            }
        ) {
            if (descriptionId != 0) {
                IronLemonDialogText(stringResource(descriptionId))
            }
        }
    }

    companion object {
        const val TAG = "SetupWarningDialogFragment"

        private const val TITLE = "Title"
        private const val DESCRIPTION = "Description"
        private const val HELP_LINK = "HelpLink"
        private const val PAGE = "Page"

        fun newInstance(
            titleId: Int,
            descriptionId: Int,
            helpLinkId: Int,
            page: Int
        ): SetupWarningDialogFragment {
            val dialog = SetupWarningDialogFragment()
            val bundle = Bundle()
            bundle.apply {
                putInt(TITLE, titleId)
                putInt(DESCRIPTION, descriptionId)
                putInt(HELP_LINK, helpLinkId)
                putInt(PAGE, page)
            }
            dialog.arguments = bundle
            return dialog
        }
    }
}
