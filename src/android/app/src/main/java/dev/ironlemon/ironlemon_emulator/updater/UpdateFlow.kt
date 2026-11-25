// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.updater

import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.fragments.ProgressDialogFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonSwitchRow
import dev.ironlemon.ironlemon_emulator.ui.modern.showIronLemonDialog
import dev.ironlemon.ironlemon_emulator.utils.Log
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The update dialogs: the check at start, and the one asked for from About. */
object UpdateFlow {
    // Once per app process: recreating the activity must not ask again.
    private var checkedAtStart = false

    // The main activity is often created twice as the app starts, and the first one can be gone
    // before the servers answer. So the check at start does not belong to an activity: its answer
    // goes to the newest one, if that one is still there.
    private var startActivity: WeakReference<FragmentActivity>? = null
    private val startScope = MainScope()

    fun checkAtStart(activity: FragmentActivity) {
        if (!AppUpdater.canUpdate || !AppUpdater.isAutoCheckEnabled(activity)) {
            return
        }
        startActivity = WeakReference(activity)
        if (checkedAtStart) {
            return
        }
        checkedAtStart = true
        startScope.launch {
            val release = withContext(Dispatchers.IO) { AppUpdater.findUpdate() }
            val target = startActivity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            startActivity = null
            if (release != null && target != null) offer(target, release)
        }
    }

    fun checkNow(activity: FragmentActivity) {
        activity.lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { AppUpdater.findUpdate() }
            when {
                release != null -> offer(activity, release)
                !AppUpdater.canUpdate -> toast(activity, R.string.update_not_for_debug)
                AppUpdater.lastCheckReachedServer -> upToDate(activity)
                else -> toast(activity, R.string.update_check_failed)
            }
        }
    }

    private fun toast(activity: FragmentActivity, text: Int) =
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    /** Says it is up to date, with the switch for the check at start. */
    private fun upToDate(activity: FragmentActivity) {
        showIronLemonDialog(activity) { dismiss ->
            var auto by remember { mutableStateOf(AppUpdater.isAutoCheckEnabled(activity)) }
            IronLemonDialogCard(
                title = stringResource(R.string.update_up_to_date),
                buttons = {
                    IronLemonButton(
                        text = stringResource(android.R.string.ok),
                        primary = true,
                        height = 44.dp,
                        onClick = dismiss
                    )
                }
            ) {
                IronLemonSwitchRow(stringResource(R.string.update_auto_check), auto) {
                    auto = it
                    AppUpdater.setAutoCheckEnabled(activity, it)
                }
            }
        }
    }

    private fun offer(activity: FragmentActivity, release: AppUpdater.Release) {
        showIronLemonDialog(activity) { dismiss ->
            IronLemonDialogCard(
                title = stringResource(R.string.update_available, release.version),
                buttons = {
                    IronLemonButton(
                        text = stringResource(R.string.update_later),
                        height = 44.dp,
                        onClick = dismiss
                    )
                    IronLemonButton(
                        text = stringResource(R.string.update_install),
                        primary = true,
                        height = 44.dp,
                        onClick = {
                            dismiss()
                            downloadAndInstall(activity, release)
                        }
                    )
                }
            ) {
                IronLemonDialogText(
                    if (release.notes.isBlank()) {
                        stringResource(R.string.update_no_notes)
                    } else {
                        ReleaseNotes.format(release.notes, release.version)
                    }
                )
            }
        }
    }

    private fun downloadAndInstall(activity: FragmentActivity, release: AppUpdater.Release) {
        val context = activity.applicationContext
        ProgressDialogFragment.newInstance(activity, R.string.update_downloading, true) { progress, _ ->
            try {
                // The callback answers whether the user cancelled; download() wants "go on".
                val apk = AppUpdater.download(context, release) { total, done ->
                    !progress(total, done)
                }
                withContext(Dispatchers.Main) {
                    AppUpdater.install(activity, apk) { error ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_install_failed, error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                Unit
            } catch (e: Exception) {
                Log.error("[Updater] Download failed: ${e.message}")
                context.getString(R.string.update_download_failed)
            }
        }.show(activity.supportFragmentManager, ProgressDialogFragment.TAG)
    }
}
