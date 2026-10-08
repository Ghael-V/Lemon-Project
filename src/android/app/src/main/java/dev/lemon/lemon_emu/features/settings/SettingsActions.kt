// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.features.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.lemon.lemon_emu.LemonApplication
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.dialogs.NetPlayDialog
import dev.lemon.lemon_emu.features.DocumentProvider
import dev.lemon.lemon_emu.features.settings.ui.SettingsSubscreen
import dev.lemon.lemon_emu.features.settings.ui.SettingsSubscreenActivity
import dev.lemon.lemon_emu.features.settings.ui.SettingsSubscreenActivityArgs
import dev.lemon.lemon_emu.fragments.MessageDialogFragment
import dev.lemon.lemon_emu.fragments.ProgressDialogFragment
import dev.lemon.lemon_emu.fragments.SystemInfoDialogFragment
import dev.lemon.lemon_emu.utils.FileUtil
import dev.lemon.lemon_emu.utils.Log
import dev.lemon.lemon_emu.utils.NativeConfig
import dev.lemon.lemon_emu.utils.PerformancePresets

/**
 * The actions the settings screens offer besides plain options: opening the other settings
 * screens, the performance dialogs, sharing logs and so on. They used to live in the settings
 * home screen; the settings categories reach them from any screen now.
 */
object SettingsActions {
    fun openSubscreen(context: Context, destination: SettingsSubscreen) {
        context.startActivity(
            Intent(context, SettingsSubscreenActivity::class.java)
                .putExtras(SettingsSubscreenActivityArgs(destination, null).toBundle())
        )
    }

    fun showPerformancePresetDialog(activity: FragmentActivity) {
        val presets = PerformancePresets.Preset.entries.toTypedArray()
        val labels = presets.map { activity.getString(it.titleRes) }.toTypedArray()

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.performance_preset)
            .setItems(labels) { dialog, which ->
                val preset = presets[which]
                PerformancePresets.apply(preset)
                NativeConfig.saveGlobalConfig()
                dialog.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.preset_applied, activity.getString(preset.titleRes)),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .show()
    }

    fun showAdaptivePerformanceDialog(activity: FragmentActivity) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val labels = arrayOf(activity.getString(R.string.enabled), activity.getString(R.string.disabled))
        val currentIndex =
            if (prefs.getBoolean(PerformancePresets.PREF_ADAPTIVE_PERFORMANCE, true)) 0 else 1

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.adaptive_performance)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.edit {
                    putBoolean(PerformancePresets.PREF_ADAPTIVE_PERFORMANCE, which == 0)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .show()
    }

    fun openMultiplayer(activity: FragmentActivity) {
        NetPlayDialog(activity).show()
    }

    fun showSystemInfo(activity: FragmentActivity) {
        SystemInfoDialogFragment.newInstance()
            .show(activity.supportFragmentManager, SystemInfoDialogFragment.TAG)
    }

    fun verifyInstalledContent(activity: FragmentActivity) {
        ProgressDialogFragment.newInstance(
            activity,
            titleId = R.string.verifying,
            cancellable = true
        ) { progressCallback, _ ->
            val result = NativeLibrary.verifyInstalledContents(progressCallback)
            return@newInstance if (progressCallback.invoke(100, 100)) {
                // Invoke the progress callback to check if the process was cancelled
                MessageDialogFragment.newInstance(
                    titleId = R.string.verify_no_result,
                    descriptionId = R.string.verify_no_result_description
                )
            } else if (result.isEmpty()) {
                MessageDialogFragment.newInstance(
                    titleId = R.string.verify_success,
                    descriptionId = R.string.operation_completed_successfully
                )
            } else {
                val failedNames = result.joinToString("\n")
                val errorMessage = LemonApplication.appContext.getString(
                    R.string.verification_failed_for,
                    failedNames
                )
                MessageDialogFragment.newInstance(
                    titleId = R.string.verify_failure,
                    descriptionString = errorMessage
                )
            }
        }.show(activity.supportFragmentManager, ProgressDialogFragment.TAG)
    }

    /** One entry for the three logs: asks which one to share. */
    fun showShareLogsDialog(activity: FragmentActivity) {
        val labels = arrayOf(
            activity.getString(R.string.share_log),
            activity.getString(R.string.share_gpu_log),
            activity.getString(R.string.share_crash_log)
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.share_logs)
            .setItems(labels) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> shareLog(activity, "lemon_log.txt", R.string.share_log, R.string.share_log_missing)
                    1 -> shareLog(activity, "lemon_gpu.log", R.string.share_gpu_log, R.string.share_gpu_log_missing)
                    else -> shareCrashLog(activity)
                }
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .show()
    }

    // Shares the current log if a game ran in this session, otherwise the previous session's.
    private fun shareLog(activity: FragmentActivity, fileName: String, titleId: Int, missingId: Int) {
        val currentLog = logFile(activity, fileName)
        val oldLog = logFile(activity, "$fileName.old.txt")

        val intent = Intent(Intent.ACTION_SEND)
            .setDataAndType(currentLog.uri, FileUtil.TEXT_PLAIN)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!Log.gameLaunched && oldLog.exists()) {
            intent.putExtra(Intent.EXTRA_STREAM, oldLog.uri)
            activity.startActivity(Intent.createChooser(intent, activity.getText(titleId)))
        } else if (currentLog.exists()) {
            intent.putExtra(Intent.EXTRA_STREAM, currentLog.uri)
            activity.startActivity(Intent.createChooser(intent, activity.getText(titleId)))
        } else {
            Toast.makeText(activity, activity.getText(missingId), Toast.LENGTH_SHORT).show()
        }
    }

    // The crash_log.txt written by LemonApplication's uncaught exception handler.
    private fun shareCrashLog(activity: FragmentActivity) {
        val crashLog = logFile(activity, "crash_log.txt")
        if (crashLog.exists()) {
            val intent = Intent(Intent.ACTION_SEND)
                .setDataAndType(crashLog.uri, FileUtil.TEXT_PLAIN)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM, crashLog.uri)
            activity.startActivity(Intent.createChooser(intent, activity.getText(R.string.share_crash_log)))
        } else {
            Toast.makeText(activity, activity.getText(R.string.share_crash_log_missing), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun logFile(context: Context, name: String): DocumentFile =
        DocumentFile.fromSingleUri(
            context,
            DocumentsContract.buildDocumentUri(
                DocumentProvider.AUTHORITY,
                "${DocumentProvider.ROOT_ID}/log/$name"
            )
        )!!

    fun openUserFolder(activity: FragmentActivity) {
        // First, try to open the user data folder directly
        try {
            activity.startActivity(documentProviderIntent(activity, Intent.ACTION_VIEW))
            return
        } catch (_: ActivityNotFoundException) {
        }

        try {
            activity.startActivity(documentProviderIntent(activity, "android.provider.action.BROWSE"))
            return
        } catch (_: ActivityNotFoundException) {
        }

        // Just try to open the file manager, with the package name used on "normal" phones and
        // then the AOSP one. Fragile, but some phones expose it no better way.
        for (packageName in listOf("com.google.android.documentsui", "com.android.documentsui")) {
            try {
                activity.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .setClassName(packageName, "com.android.documentsui.files.FilesActivity")
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                showNoLinkNotification(activity)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }

        Toast.makeText(activity, activity.getString(R.string.no_file_manager), Toast.LENGTH_LONG).show()
    }

    private fun documentProviderIntent(context: Context, action: String): Intent =
        Intent(action)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setData(DocumentsContract.buildRootUri("${context.packageName}.user", DocumentProvider.ROOT_ID))
            .addFlags(
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

    private fun showNoLinkNotification(context: Context) {
        val builder = NotificationCompat.Builder(
            context,
            context.getString(R.string.notice_notification_channel_id)
        )
            .setSmallIcon(R.drawable.ic_stat_notification_logo)
            .setContentTitle(context.getString(R.string.notification_no_directory_link))
            .setContentText(context.getString(R.string.notification_no_directory_link_description))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                context,
                context.getString(R.string.notification_permission_not_granted),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        NotificationManagerCompat.from(context).notify(0, builder.build())
    }
}
