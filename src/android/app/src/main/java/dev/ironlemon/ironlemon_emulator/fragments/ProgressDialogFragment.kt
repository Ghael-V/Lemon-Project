// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonColors
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonProgressBar
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonType
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.TaskViewModel
import dev.ironlemon.ironlemon_emulator.utils.collect

class ProgressDialogFragment : IronLemonComposeDialogFragment() {
    private val taskViewModel: TaskViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Closed by the task finishing (or by Cancel), never by a tap outside or Back.
        isCancelable = false
        if (!taskViewModel.isRunning.value) {
            taskViewModel.runTask()
        }
    }

    @Composable
    override fun DialogContent() {
        val cancellable = requireArguments().getBoolean(CANCELLABLE)
        val cancelled by taskViewModel.cancelled.collectAsState()
        val progress by taskViewModel.progress.collectAsState()
        val maxProgress by taskViewModel.maxProgress.collectAsState()
        val message by taskViewModel.message.collectAsState()
        val fraction = if (progress != 0.0 && maxProgress > 0.0 && !cancelled) {
            (progress / maxProgress).toFloat().coerceIn(0f, 1f)
        } else {
            null
        }

        IronLemonDialogCard(
            title = stringResource(
                if (cancelled) R.string.cancelling else requireArguments().getInt(TITLE)
            ),
            buttons = if (cancellable && !cancelled) {
                {
                    IronLemonButton(
                        text = stringResource(android.R.string.cancel),
                        height = 44.dp,
                        onClick = { taskViewModel.setCancelled(true) }
                    )
                }
            } else {
                null
            }
        ) {
            if (message.isNotEmpty()) {
                BasicText(
                    message,
                    style = IronLemonType.Body.copy(color = IronLemonColors.Text),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
            }
            IronLemonProgressBar(fraction)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        taskViewModel.isComplete.collect(viewLifecycleOwner) {
            if (it) {
                // The task can finish while the app is in the background, after Android saved
                // its state: a plain dismiss() or show() then throws and takes the app down.
                dismissAllowingStateLoss()
                when (val result = taskViewModel.result.value) {
                    is String -> Toast.makeText(
                        requireContext(),
                        result,
                        Toast.LENGTH_LONG
                    ).show()

                    is MessageDialogFragment -> requireActivity().supportFragmentManager
                        .beginTransaction()
                        .add(result, MessageDialogFragment.TAG)
                        .commitAllowingStateLoss()

                    else -> {
                        // Do nothing
                    }
                }
                taskViewModel.clear()
            }
        }
    }

    companion object {
        const val TAG = "IndeterminateProgressDialogFragment"

        private const val TITLE = "Title"
        private const val CANCELLABLE = "Cancellable"

        fun newInstance(
            activity: FragmentActivity,
            titleId: Int,
            cancellable: Boolean = false,
            task: suspend (
                progressCallback: (max: Long, progress: Long) -> Boolean,
                messageCallback: (message: String) -> Unit
            ) -> Any
        ): ProgressDialogFragment {
            val dialog = ProgressDialogFragment()
            val args = Bundle()
            ViewModelProvider(activity)[TaskViewModel::class.java].task = task
            args.putInt(TITLE, titleId)
            args.putBoolean(CANCELLABLE, cancellable)
            dialog.arguments = args
            return dialog
        }
    }
}
