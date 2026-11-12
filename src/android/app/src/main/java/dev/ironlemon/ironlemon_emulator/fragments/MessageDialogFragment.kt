// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonColors
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonType
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.MessageDialogViewModel
import dev.ironlemon.ironlemon_emulator.utils.Log

class MessageDialogFragment : IronLemonComposeDialogFragment() {
    private val messageDialogViewModel: MessageDialogViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = requireArguments().getBoolean(DISMISSIBLE)
        if (requireArguments().getBoolean(CLEAR_ACTIONS)) {
            messageDialogViewModel.positiveAction = null
        }
    }

    private fun text(idKey: String, stringKey: String): String {
        val id = requireArguments().getInt(idKey)
        return if (id != 0) getString(id) else requireArguments().getString(stringKey).orEmpty()
    }

    @Composable
    override fun DialogContent() {
        val title = text(TITLE_ID, TITLE_STRING)
        val description = text(DESCRIPTION_ID, DESCRIPTION_STRING)
        val positiveButton = text(POSITIVE_BUTTON_TITLE_ID, POSITIVE_BUTTON_TITLE_STRING).ifEmpty {
            if (messageDialogViewModel.positiveAction != null) {
                getString(android.R.string.ok)
            } else {
                getString(R.string.close)
            }
        }
        val negativeButton = text(NEGATIVE_BUTTON_TITLE_ID, NEGATIVE_BUTTON_TITLE_STRING)
            .ifEmpty { getString(android.R.string.cancel) }
        val helpLinkId = requireArguments().getInt(HELP_LINK)
        // A dialog that asks to confirm something can always be turned down.
        val showNegative = messageDialogViewModel.negativeAction != null ||
            messageDialogViewModel.positiveAction != null ||
            requireArguments().getBoolean(SHOW_NEGATIVE_BUTTON)

        IronLemonDialogCard(
            title = title,
            buttons = {
                if (helpLinkId != 0) {
                    IronLemonButton(text = getString(R.string.learn_more), height = 44.dp, onClick = {
                        openLink(getString(helpLinkId))
                        dismiss()
                    })
                }
                if (showNegative) {
                    IronLemonButton(text = negativeButton, height = 44.dp, onClick = {
                        messageDialogViewModel.negativeAction?.invoke()
                        dismiss()
                    })
                }
                IronLemonButton(text = positiveButton, primary = true, height = 44.dp, onClick = {
                    messageDialogViewModel.positiveAction?.invoke()
                    dismiss()
                })
            }
        ) {
            if (description.isNotEmpty()) {
                BasicText(
                    AnnotatedString.fromHtml(description),
                    style = IronLemonType.Body.copy(color = IronLemonColors.Text),
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }

    private fun openLink(link: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        startActivity(intent)
    }

    companion object {
        const val TAG = "MessageDialogFragment"

        private const val TITLE_ID = "Title"
        private const val TITLE_STRING = "TitleString"
        private const val DESCRIPTION_ID = "DescriptionId"
        private const val DESCRIPTION_STRING = "DescriptionString"
        private const val HELP_LINK = "Link"
        private const val DISMISSIBLE = "Dismissible"
        private const val CLEAR_ACTIONS = "ClearActions"
        private const val POSITIVE_BUTTON_TITLE_ID = "PositiveButtonTitleId"
        private const val POSITIVE_BUTTON_TITLE_STRING = "PositiveButtonTitleString"
        private const val SHOW_NEGATIVE_BUTTON = "ShowNegativeButton"
        private const val NEGATIVE_BUTTON_TITLE_ID = "NegativeButtonTitleId"
        private const val NEGATIVE_BUTTON_TITLE_STRING = "NegativeButtonTitleString"

        /**
         * Creates a new [MessageDialogFragment] instance.
         * @param activity Activity that will hold a [MessageDialogViewModel] instance if using
         * [positiveAction] or [negativeAction].
         * @param titleId String resource ID that will be used for the title. [titleString] used if 0.
         * @param titleString String that will be used for the title. No title is set if empty.
         * @param descriptionId String resource ID that will be used for the description.
         * [descriptionString] used if 0.
         * @param descriptionString String that will be used for the description.
         * No description is set if empty.
         * @param helpLinkId String resource ID that contains a help link. Will be added as a neutral
         * button with the title R.string.help.
         * @param dismissible Whether the dialog is dismissible or not. Typically used to ensure that
         * the user clicks on one of the dialog buttons before closing.
         * @param positiveButtonTitleId String resource ID that will be used for the positive button.
         * [positiveButtonTitleString] used if 0.
         * @param positiveButtonTitleString String that will be used for the positive button.
         * android.R.string.close used if empty. android.R.string.ok will be used if [positiveAction]
         * is not null.
         * @param positiveAction Lambda to run when the positive button is clicked.
         * @param showNegativeButton Normally the negative button isn't shown if there is no
         * [negativeAction] set. This can override that behavior to always show a button.
         * @param negativeButtonTitleId String resource ID that will be used for the negative button.
         * [negativeButtonTitleString] used if 0.
         * @param negativeButtonTitleString String that will be used for the negative button.
         * android.R.string.cancel used if empty.
         * @param negativeAction Lambda to run when the negative button is clicked
         */
        fun newInstance(
            activity: FragmentActivity? = null,
            titleId: Int = 0,
            titleString: String = "",
            descriptionId: Int = 0,
            descriptionString: String = "",
            helpLinkId: Int = 0,
            dismissible: Boolean = true,
            positiveButtonTitleId: Int = 0,
            positiveButtonTitleString: String = "",
            positiveAction: (() -> Unit)? = null,
            showNegativeButton: Boolean = false,
            negativeButtonTitleId: Int = 0,
            negativeButtonTitleString: String = "",
            negativeAction: (() -> Unit)? = null
        ): MessageDialogFragment {
            var clearActions = false
            if (activity != null) {
                ViewModelProvider(activity)[MessageDialogViewModel::class.java].apply {
                    clear()
                    this.positiveAction = positiveAction
                    this.negativeAction = negativeAction
                }
            } else {
                clearActions = true
            }

            if (activity == null && (positiveAction == null || negativeAction == null)) {
                Log.warning("[$TAG] Tried to set action with no activity!")
            }

            val dialog = MessageDialogFragment()
            val bundle = Bundle().apply {
                putInt(TITLE_ID, titleId)
                putString(TITLE_STRING, titleString)
                putInt(DESCRIPTION_ID, descriptionId)
                putString(DESCRIPTION_STRING, descriptionString)
                putInt(HELP_LINK, helpLinkId)
                putBoolean(DISMISSIBLE, dismissible)
                putBoolean(CLEAR_ACTIONS, clearActions)
                putInt(POSITIVE_BUTTON_TITLE_ID, positiveButtonTitleId)
                putString(POSITIVE_BUTTON_TITLE_STRING, positiveButtonTitleString)
                putBoolean(SHOW_NEGATIVE_BUTTON, showNegativeButton)
                putInt(NEGATIVE_BUTTON_TITLE_ID, negativeButtonTitleId)
                putString(NEGATIVE_BUTTON_TITLE_STRING, negativeButtonTitleString)
            }
            dialog.arguments = bundle
            return dialog
        }
    }
}
