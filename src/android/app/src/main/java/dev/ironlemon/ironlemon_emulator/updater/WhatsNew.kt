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

/**
 * "What's new": shown once per version, the first time the app opens past setup with it, on a new
 * install too (Iron Lemon is an app of its own: nobody arrives by updating another app).
 */
object WhatsNew {
    private const val PREF_LAST_VERSION = "whats_new_last_version"

    fun showIfNew(activity: FragmentActivity) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val current = BuildConfig.VERSION_NAME
        if (prefs.getString(PREF_LAST_VERSION, null) == current) {
            return
        }
        prefs.edit { putString(PREF_LAST_VERSION, current) }
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
}
