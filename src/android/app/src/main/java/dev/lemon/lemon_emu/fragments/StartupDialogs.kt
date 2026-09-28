// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.fragments

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.databinding.DialogSupportBinding
import dev.lemon.lemon_emu.features.settings.model.BooleanSetting
import dev.lemon.lemon_emu.ui.main.MainActivity
import dev.lemon.lemon_emu.utils.NativeConfig

// The startup notices are DialogFragments so the system restores them, once, when MainActivity is
// recreated (theme setup right after launch, every rotation). Plain dialogs got closed by that
// without the user seeing them, or shown twice.

/** Ko-fi / Buy Me a Coffee note. */
class SupportDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val binding = DialogSupportBinding.inflate(layoutInflater)
        binding.buttonKofi.setOnClickListener { openAndClose(R.string.kofi_link) }
        binding.buttonBuymeacoffee.setOnClickListener { openAndClose(R.string.buymeacoffee_link) }
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.support_prompt_title)
            .setView(binding.root)
            .setPositiveButton(R.string.not_now) { _, _ -> markSeen(context) }
            .setNegativeButton(R.string.dont_show_again) { _, _ ->
                markSeen(context)
                preferences(context).edit { putBoolean(PREF_DISABLED, true) }
            }
            .create()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        markSeen(requireContext())
    }

    private fun openAndClose(linkId: Int) {
        markSeen(requireContext())
        startActivity(Intent(Intent.ACTION_VIEW, getString(linkId).toUri()))
        dismiss()
    }

    companion object {
        const val TAG = "SupportDialogFragment"
        private const val PREF_DISABLED = "support_prompt_disabled"
        private const val PREF_VERSION = "support_prompt_version"
        private const val PREF_LAUNCHES = "support_prompt_launches"
        private const val EVERY_LAUNCHES = 10

        private fun preferences(context: Context) =
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

        private fun markSeen(context: Context) = preferences(context).edit {
            putString(PREF_VERSION, NativeLibrary.getBuildVersion())
            putInt(PREF_LAUNCHES, 0)
        }

        /**
         * Call once per app launch. Shows the note on the first launch of each version and again
         * every EVERY_LAUNCHES launches after "Not now", until "Don't show again".
         */
        fun onAppLaunch(context: Context, fragmentManager: FragmentManager) {
            val preferences = preferences(context)
            if (preferences.getBoolean(PREF_DISABLED, false)) {
                return
            }
            if (preferences.getString(PREF_VERSION, null) == NativeLibrary.getBuildVersion()) {
                val launches = preferences.getInt(PREF_LAUNCHES, 0) + 1
                preferences.edit { putInt(PREF_LAUNCHES, launches) }
                if (launches < EVERY_LAUNCHES) {
                    return
                }
            }
            if (fragmentManager.findFragmentByTag(TAG) == null) {
                SupportDialogFragment().show(fragmentManager, TAG)
            }
        }
    }
}

/** "A new version is available" notice. */
class UpdateDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        val release = NativeLibrary.UpdateResult(
            tag = arguments.getString(TAG_NAME).orEmpty(),
            title = arguments.getString(TITLE).orEmpty(),
            url = arguments.getString(URL).orEmpty(),
            assets = arguments.getStringArrayList(ASSETS)?.toMutableList() ?: mutableListOf()
        )
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_available)
            .setMessage(getString(R.string.update_available_description, release.title))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                (activity as? MainActivity)?.onUpdateAccepted(release)
            }
            .setNeutralButton(R.string.cancel, null)
            .setNegativeButton(R.string.dont_show_again) { _, _ ->
                BooleanSetting.ENABLE_UPDATE_CHECKS.setBoolean(false)
                NativeConfig.saveGlobalConfig()
            }
            .create()
    }

    companion object {
        const val TAG = "UpdateDialogFragment"
        private const val TAG_NAME = "tag"
        private const val TITLE = "title"
        private const val URL = "url"
        private const val ASSETS = "assets"

        fun show(fragmentManager: FragmentManager, release: NativeLibrary.UpdateResult) {
            if (fragmentManager.findFragmentByTag(TAG) != null) {
                return
            }
            UpdateDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(TAG_NAME, release.tag)
                    putString(TITLE, release.title)
                    putString(URL, release.url)
                    putStringArrayList(ASSETS, ArrayList(release.assets))
                }
            }.show(fragmentManager, TAG)
        }
    }
}
