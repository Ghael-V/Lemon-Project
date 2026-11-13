// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.updater

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import dev.ironlemon.ironlemon_emulator.BuildConfig
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText
import dev.ironlemon.ironlemon_emulator.ui.modern.showIronLemonDialog

/** "What's new": shown once after the app is updated to a new version (not on a new install). */
object WhatsNew {
    private const val PREF_LAST_VERSION = "whats_new_last_version"

    fun showIfUpdated(activity: FragmentActivity, firstInstall: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val last = prefs.getString(PREF_LAST_VERSION, null)
        val current = BuildConfig.VERSION_NAME
        if (last == current) {
            return
        }
        prefs.edit { putString(PREF_LAST_VERSION, current) }
        // A fresh install has just been through setup: nothing to compare with.
        if (firstInstall || last == null && !isUpdateFromLemon(activity)) {
            return
        }
        showIronLemonDialog(activity) { dismiss ->
            IronLemonDialogCard(
                title = stringResource(R.string.whats_new_title, current),
                buttons = {
                    IronLemonButton(
                        text = stringResource(android.R.string.ok),
                        primary = true,
                        height = 44.dp,
                        onClick = dismiss
                    )
                }
            ) {
                IronLemonDialogText(stringResource(R.string.whats_new_body))
            }
        }
    }

    // Updated over Lemon 1.x (same app, its settings already there): tell them what changed.
    private fun isUpdateFromLemon(activity: FragmentActivity): Boolean {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return info.firstInstallTime != info.lastUpdateTime
    }
}
