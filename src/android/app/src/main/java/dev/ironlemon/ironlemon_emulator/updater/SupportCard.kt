// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.updater

import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import dev.ironlemon.ironlemon_emulator.BuildConfig
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButtonRow
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText
import dev.ironlemon.ironlemon_emulator.ui.modern.showIronLemonDialog

/**
 * The support card: Discord, Ko-fi and Buy Me a Coffee. Shown on the first launch of each version
 * and again every [EVERY_LAUNCHES] launches after "Not now", until "Don't show again".
 *
 * Only what the user does counts as seen: the main activity is often recreated right after it
 * starts, which takes a card shown in the first onCreate with it; the recreated one shows it again.
 */
object SupportCard {
    private const val PREF_DISABLED = "support_prompt_disabled"
    private const val PREF_VERSION = "support_prompt_version"
    private const val PREF_LAUNCHES = "support_prompt_launches"
    private const val EVERY_LAUNCHES = 10

    // Decided once per launch, kept across a recreation until the user answers.
    private var pending = false

    /** Call from MainActivity.onCreate past setup; [newLaunch]: not a recreation. */
    fun onAppLaunch(activity: FragmentActivity, newLaunch: Boolean) {
        if (newLaunch) {
            pending = isDue(activity)
        }
        if (pending) {
            show(activity)
        }
    }

    private fun isDue(activity: FragmentActivity): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        if (prefs.getBoolean(PREF_DISABLED, false)) {
            return false
        }
        if (prefs.getString(PREF_VERSION, null) != BuildConfig.VERSION_NAME) {
            return true
        }
        val launches = prefs.getInt(PREF_LAUNCHES, 0) + 1
        prefs.edit { putInt(PREF_LAUNCHES, launches) }
        return launches >= EVERY_LAUNCHES
    }

    private fun markSeen(activity: FragmentActivity, forever: Boolean = false) {
        pending = false
        PreferenceManager.getDefaultSharedPreferences(activity).edit {
            putString(PREF_VERSION, BuildConfig.VERSION_NAME)
            putInt(PREF_LAUNCHES, 0)
            if (forever) {
                putBoolean(PREF_DISABLED, true)
            }
        }
    }

    private fun show(activity: FragmentActivity) {
        showIronLemonDialog(activity, onDismiss = { markSeen(activity) }) { dismiss ->
            fun open(linkRes: Int) {
                activity.startActivity(Intent(Intent.ACTION_VIEW, activity.getString(linkRes).toUri()))
                dismiss()
            }
            IronLemonDialogCard(
                title = stringResource(R.string.support_prompt_title),
                buttons = {
                    IronLemonButton(
                        text = stringResource(R.string.dont_show_again),
                        height = 44.dp,
                        onClick = {
                            markSeen(activity, forever = true)
                            dismiss()
                        }
                    )
                    IronLemonButton(
                        text = stringResource(R.string.not_now),
                        primary = true,
                        height = 44.dp,
                        onClick = dismiss
                    )
                }
            ) {
                IronLemonDialogText(stringResource(R.string.support_prompt_description))
                Spacer(Modifier.height(20.dp))
                IronLemonButtonRow {
                    IronLemonButton(
                        text = stringResource(R.string.join_discord_button),
                        iconRes = R.drawable.ic_discord,
                        height = 44.dp,
                        onClick = { open(R.string.discord_link) }
                    )
                    IronLemonButton(
                        text = stringResource(R.string.kofi_link_button),
                        iconRes = R.drawable.ic_coffee,
                        height = 44.dp,
                        onClick = { open(R.string.kofi_link) }
                    )
                    IronLemonButton(
                        text = stringResource(R.string.buymeacoffee_link_button),
                        iconRes = R.drawable.ic_coffee,
                        height = 44.dp,
                        onClick = { open(R.string.buymeacoffee_link) }
                    )
                }
            }
        }
    }
}
