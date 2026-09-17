// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.fragments

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.core.content.edit
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import dev.lemon.lemon_emu.HomeNavigationDirections
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.LemonApplication
import dev.lemon.lemon_emu.adapters.HomeSettingAdapter
import dev.lemon.lemon_emu.databinding.FragmentHomeSettingsBinding
import dev.lemon.lemon_emu.features.DocumentProvider
import dev.lemon.lemon_emu.features.fetcher.SpacingItemDecoration
import dev.lemon.lemon_emu.features.settings.model.Settings
import dev.lemon.lemon_emu.features.settings.ui.SettingsSubscreen
import dev.lemon.lemon_emu.model.DriverViewModel
import dev.lemon.lemon_emu.model.HomeSetting
import dev.lemon.lemon_emu.model.HomeViewModel
import dev.lemon.lemon_emu.ui.main.MainActivity
import dev.lemon.lemon_emu.utils.FileUtil
import dev.lemon.lemon_emu.utils.GpuDriverHelper
import dev.lemon.lemon_emu.utils.Log
import dev.lemon.lemon_emu.utils.LosslessScalingHelper
import dev.lemon.lemon_emu.utils.NativeConfig
import dev.lemon.lemon_emu.utils.PerformancePresets
import dev.lemon.lemon_emu.utils.ViewUtils.updateMargins

class HomeSettingsFragment : Fragment() {
    private var _binding: FragmentHomeSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var mainActivity: MainActivity

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val driverViewModel: DriverViewModel by activityViewModels()

    private var showAdvancedSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeSettingsBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setStatusBarShadeVisibility(visible = false)
        mainActivity = requireActivity() as MainActivity
        binding.toolbarHomeSettings.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        binding.toolbarHomeSettings.title = getString(R.string.preferences_settings)

        binding.homeSettingsList.apply {
            layoutManager =
                GridLayoutManager(requireContext(), resources.getInteger(R.integer.grid_columns))
            val spacing = resources.getDimensionPixelSize(R.dimen.spacing_small)
            addItemDecoration(SpacingItemDecoration(spacing))
        }
        refreshOptionsList()

        setInsets()
    }

    // Only the settings people actually reach for day to day. Everything else lives behind
    // the "More options" toggle below so first-time setup isn't a wall of 17 identical cards.
    private fun buildEssentialOptions(): MutableList<HomeSetting> =
        mutableListOf<HomeSetting>().apply {
            add(
                HomeSetting(
                    R.string.gpu_driver_manager,
                    R.string.install_gpu_driver_description,
                    R.drawable.ic_build,
                    {
                        val action = HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                            SettingsSubscreen.DRIVER_MANAGER,
                            null
                        )
                        binding.root.findNavController().navigate(action)
                    },
                    { true },
                    R.string.custom_driver_not_supported,
                    R.string.custom_driver_not_supported_description,
                    driverViewModel.selectedDriverTitle
                )
            )
            if (GpuDriverHelper.isAdrenoGpu()) {
                add(
                    HomeSetting(
                        R.string.freedreno_settings_title,
                        R.string.gpu_driver_settings,
                        R.drawable.ic_graphics,
                        {
                            val action =
                                HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                                    SettingsSubscreen.FREEDRENO_SETTINGS,
                                    null
                                )
                            binding.root.findNavController().navigate(action)
                        }
                    )
                )
            }
            add(
                HomeSetting(
                    R.string.preferences_controls,
                    R.string.preferences_controls_description,
                    R.drawable.ic_controller,
                    {
                        val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                            null,
                            Settings.MenuTag.SECTION_INPUT
                        )
                        binding.root.findNavController().navigate(action)
                    }
                )
            )
            add(
                HomeSetting(
                    R.string.app_settings,
                    R.string.app_settings_description,
                    R.drawable.ic_palette,
                    {
                        val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                            null,
                            Settings.MenuTag.SECTION_APP_SETTINGS
                        )
                        binding.root.findNavController().navigate(action)
                    }
                )
            )
            add(
                HomeSetting(
                    R.string.performance_preset,
                    R.string.performance_preset_description,
                    R.drawable.ic_frames,
                    { showPerformancePresetDialog() }
                )
            )
            add(
                HomeSetting(
                    R.string.thermal_auto_throttle,
                    R.string.thermal_auto_throttle_description,
                    R.drawable.ic_frames,
                    { showThermalAutoThrottleDialog() }
                )
            )
            add(
                HomeSetting(
                    R.string.manage_game_folders,
                    R.string.select_games_folder_description,
                    R.drawable.ic_add,
                    {
                        val action = HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                            SettingsSubscreen.GAME_FOLDERS,
                            null
                        )
                        binding.root.findNavController().navigate(action)
                    }
                )
            )
        }

    private fun showPerformancePresetDialog() {
        val presets = PerformancePresets.Preset.entries.toTypedArray()
        val labels = presets.map { getString(it.titleRes) }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.performance_preset)
            .setItems(labels) { dialog, which ->
                val preset = presets[which]
                PerformancePresets.apply(preset)
                NativeConfig.saveGlobalConfig()
                dialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.preset_applied, getString(preset.titleRes)),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .show()
    }

    private fun showThermalAutoThrottleDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val labels = arrayOf(getString(R.string.enabled), getString(R.string.disabled))
        val currentIndex =
            if (prefs.getBoolean(PerformancePresets.PREF_THERMAL_AUTO_THROTTLE, false)) 0 else 1

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.thermal_auto_throttle)
            .setMessage(R.string.thermal_auto_throttle_description)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.edit {
                    putBoolean(PerformancePresets.PREF_THERMAL_AUTO_THROTTLE, which == 0)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .show()
    }

    private fun buildAdvancedOptions(): MutableList<HomeSetting> =
        mutableListOf<HomeSetting>().apply {
            add(
                HomeSetting(
                    R.string.advanced_settings,
                    R.string.settings_description,
                    R.drawable.ic_settings,
                    {
                        val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                            null,
                            Settings.MenuTag.SECTION_ROOT
                        )
                        binding.root.findNavController().navigate(action)
                    }
                )
            )
            add(
                HomeSetting(
                    R.string.profile_manager,
                    R.string.profile_manager_description,
                    R.drawable.ic_account_circle,
                    {
                        val action = HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                            SettingsSubscreen.PROFILE_MANAGER,
                            null
                        )
                        binding.root.findNavController().navigate(action)
                    }
                )
            )
            add(
                HomeSetting(
                    R.string.post_processing,
                    R.string.post_processing_description,
                    R.drawable.ic_post_processing,
                    {
                        val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                            null,
                            Settings.MenuTag.SECTION_POST_PROCESSING
                        )
                        binding.root.findNavController().navigate(action)
                    }
                )
            )
            add(
                HomeSetting(
                    R.string.lossless_scaling,
                    R.string.lossless_scaling_description,
                    R.drawable.ic_duck,
                    {
                        val action = HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                            SettingsSubscreen.LOSSLESS_MANAGER,
                            null
                        )
                        binding.root.findNavController().navigate(action)
                    },
                    { true },
                    0,
                    0,
                    LosslessScalingHelper.statusText
                )
            )
            add(
                HomeSetting(
                    R.string.multiplayer,
                    R.string.multiplayer_description,
                    R.drawable.ic_two_users,
                    {
                        mainActivity.displayMultiplayerDialog()
                    }
                )
            )
            add(
                HomeSetting(
                    R.string.applets,
                    R.string.applets_description,
                    R.drawable.ic_applet,
                    {
                        val action = HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                            SettingsSubscreen.APPLET_LAUNCHER,
                            null
                        )
                        binding.root.findNavController().navigate(action)
                    },
                    { NativeLibrary.isFirmwareAvailable() },
                    R.string.applets_error_firmware,
                    R.string.applets_error_description
                )
            )
            add(
                HomeSetting(
                    R.string.manage_lemon_data,
                    R.string.manage_lemon_data_description,
                    R.drawable.ic_install,
                    {
                        val action = HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                            SettingsSubscreen.INSTALLABLE,
                            null
                        )
                        binding.root.findNavController().navigate(action)
                    }
                )
            )
            add(
                HomeSetting(
                    R.string.verify_installed_content,
                    R.string.verify_installed_content_description,
                    R.drawable.ic_check_circle,
                    {
                        ProgressDialogFragment.newInstance(
                            requireActivity(),
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
                        }.show(parentFragmentManager, ProgressDialogFragment.TAG)
                    }
                )
            )
            add(
                HomeSetting(
                    R.string.share_log,
                    R.string.share_log_description,
                    R.drawable.ic_log,
                    { shareLog() }
                )
            )
            add(
                HomeSetting(
                    R.string.share_gpu_log,
                    R.string.share_gpu_log_description,
                    R.drawable.ic_log,
                    { shareGpuLog() }
                )
            )
            add(
                HomeSetting(
                    R.string.share_crash_log,
                    R.string.share_crash_log_description,
                    R.drawable.ic_log,
                    { shareCrashLog() }
                )
            )
            add(
                HomeSetting(
                    R.string.open_user_folder,
                    R.string.open_user_folder_description,
                    R.drawable.ic_folder_open,
                    { openFileManager() }
                )
            )
            add(
                HomeSetting(
                    R.string.system_information,
                    R.string.system_information_description,
                    R.drawable.ic_system,
                    {
                        SystemInfoDialogFragment.newInstance()
                            .show(parentFragmentManager, SystemInfoDialogFragment.TAG)
                    }
                )
            )
            add(
                HomeSetting(
                    R.string.about,
                    R.string.about_description,
                    R.drawable.ic_info_outline,
                    {
                        val action = HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                            SettingsSubscreen.ABOUT,
                            null
                        )
                        binding.root.findNavController().navigate(action)
                    }
                )
            )
        }

    private fun buildOptionsList(): MutableList<HomeSetting> {
        val options = buildEssentialOptions()
        options.add(
            HomeSetting(
                if (showAdvancedSettings) R.string.home_hide_advanced else R.string.home_show_advanced,
                if (showAdvancedSettings) {
                    R.string.home_hide_advanced_description
                } else {
                    R.string.home_show_advanced_description
                },
                R.drawable.ic_dropdown_arrow,
                {
                    showAdvancedSettings = !showAdvancedSettings
                    refreshOptionsList()
                }
            )
        )
        if (showAdvancedSettings) {
            options.addAll(buildAdvancedOptions())
        }
        return options
    }

    private fun refreshOptionsList() {
        binding.homeSettingsList.adapter = HomeSettingAdapter(
            requireActivity() as AppCompatActivity,
            viewLifecycleOwner,
            buildOptionsList()
        )
    }

    override fun onStart() {
        super.onStart()
        exitTransition = null
    }

    override fun onResume() {
        super.onResume()
        driverViewModel.updateDriverNameForGame(null)
        LosslessScalingHelper.refreshStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun openFileManager() {
        // First, try to open the user data folder directly
        try {
            startActivity(getFileManagerIntentOnDocumentProvider(Intent.ACTION_VIEW))
            return
        } catch (_: ActivityNotFoundException) {
        }

        try {
            startActivity(getFileManagerIntentOnDocumentProvider("android.provider.action.BROWSE"))
            return
        } catch (_: ActivityNotFoundException) {
        }

        // Just try to open the file manager, try the package name used on "normal" phones
        try {
            startActivity(getFileManagerIntent("com.google.android.documentsui"))
            showNoLinkNotification()
            return
        } catch (_: ActivityNotFoundException) {
        }

        try {
            // Next, try the AOSP package name
            startActivity(getFileManagerIntent("com.android.documentsui"))
            showNoLinkNotification()
            return
        } catch (_: ActivityNotFoundException) {
        }

        Toast.makeText(
            requireContext(),
            resources.getString(R.string.no_file_manager),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun getFileManagerIntent(packageName: String): Intent {
        // Fragile, but some phones don't expose the system file manager in any better way
        val intent = Intent(Intent.ACTION_MAIN)
        intent.setClassName(packageName, "com.android.documentsui.files.FilesActivity")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return intent
    }

    private fun getFileManagerIntentOnDocumentProvider(action: String): Intent {
        val authority = "${requireContext().packageName}.user"
        val intent = Intent(action)
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        intent.data = DocumentsContract.buildRootUri(authority, DocumentProvider.ROOT_ID)
        intent.addFlags(
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        return intent
    }

    private fun showNoLinkNotification() {
        val builder = NotificationCompat.Builder(
            requireContext(),
            getString(R.string.notice_notification_channel_id)
        )
            .setSmallIcon(R.drawable.ic_stat_notification_logo)
            .setContentTitle(getString(R.string.notification_no_directory_link))
            .setContentText(getString(R.string.notification_no_directory_link_description))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        // TODO: Make the click action for this notification lead to a help article

        with(NotificationManagerCompat.from(requireContext())) {
            if (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    requireContext(),
                    resources.getString(R.string.notification_permission_not_granted),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            notify(0, builder.build())
        }
    }

    // Share the current log if we just returned from a game but share the old log
    // if we just started the app and the old log exists.
    private fun shareLog() {
        val currentLog = DocumentFile.fromSingleUri(
            mainActivity,
            DocumentsContract.buildDocumentUri(
                DocumentProvider.AUTHORITY,
                "${DocumentProvider.ROOT_ID}/log/lemon_log.txt"
            )
        )!!
        val oldLog = DocumentFile.fromSingleUri(
            mainActivity,
            DocumentsContract.buildDocumentUri(
                DocumentProvider.AUTHORITY,
                "${DocumentProvider.ROOT_ID}/log/lemon_log.txt.old.txt"
            )
        )!!

        val intent = Intent(Intent.ACTION_SEND)
            .setDataAndType(currentLog.uri, FileUtil.TEXT_PLAIN)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!Log.gameLaunched && oldLog.exists()) {
            intent.putExtra(Intent.EXTRA_STREAM, oldLog.uri)
            startActivity(Intent.createChooser(intent, getText(R.string.share_log)))
        } else if (currentLog.exists()) {
            intent.putExtra(Intent.EXTRA_STREAM, currentLog.uri)
            startActivity(Intent.createChooser(intent, getText(R.string.share_log)))
        } else {
            Toast.makeText(
                requireContext(),
                getText(R.string.share_log_missing),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun shareGpuLog() {
        val currentLog = DocumentFile.fromSingleUri(
            mainActivity,
            DocumentsContract.buildDocumentUri(
                DocumentProvider.AUTHORITY,
                "${DocumentProvider.ROOT_ID}/log/lemon_gpu.log"
            )
        )!!
        val oldLog = DocumentFile.fromSingleUri(
            mainActivity,
            DocumentsContract.buildDocumentUri(
                DocumentProvider.AUTHORITY,
                "${DocumentProvider.ROOT_ID}/log/lemon_gpu.log.old.txt"
            )
        )!!

        val intent = Intent(Intent.ACTION_SEND)
            .setDataAndType(currentLog.uri, FileUtil.TEXT_PLAIN)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!Log.gameLaunched && oldLog.exists()) {
            intent.putExtra(Intent.EXTRA_STREAM, oldLog.uri)
            startActivity(Intent.createChooser(intent, getText(R.string.share_gpu_log)))
        } else if (currentLog.exists()) {
            intent.putExtra(Intent.EXTRA_STREAM, currentLog.uri)
            startActivity(Intent.createChooser(intent, getText(R.string.share_gpu_log)))
        } else {
            Toast.makeText(
                requireContext(),
                getText(R.string.share_gpu_log_missing),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Shares the on-device crash_log.txt (written by LemonApplication's uncaught
    // exception handler) so a user can report a crash without needing adb.
    private fun shareCrashLog() {
        val crashLog = DocumentFile.fromSingleUri(
            mainActivity,
            DocumentsContract.buildDocumentUri(
                DocumentProvider.AUTHORITY,
                "${DocumentProvider.ROOT_ID}/log/crash_log.txt"
            )
        )!!

        if (crashLog.exists()) {
            val intent = Intent(Intent.ACTION_SEND)
                .setDataAndType(crashLog.uri, FileUtil.TEXT_PLAIN)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM, crashLog.uri)
            startActivity(Intent.createChooser(intent, getText(R.string.share_crash_log)))
        } else {
            Toast.makeText(
                requireContext(),
                getText(R.string.share_crash_log_missing),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            binding.appbarHomeSettings.updateMargins(
                left = barInsets.left + cutoutInsets.left,
                right = barInsets.right + cutoutInsets.right
            )

            binding.scrollViewSettings.updatePadding(
                bottom = barInsets.bottom
            )

            binding.homeSettingsList.updatePadding(
                left = barInsets.left + cutoutInsets.left,
                right = barInsets.right + cutoutInsets.right
            )

            windowInsets
        }
}
