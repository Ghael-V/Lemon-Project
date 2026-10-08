// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.fragments

import dev.lemon.lemon_emu.ui.modern.UiMode
import dev.lemon.lemon_emu.ui.modern.ModernHomeSettings
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.flow.MutableStateFlow
import dev.lemon.lemon_emu.HomeNavigationDirections
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.LemonApplication
import dev.lemon.lemon_emu.adapters.HomeSettingAdapter
import dev.lemon.lemon_emu.databinding.FragmentHomeSettingsBinding
import dev.lemon.lemon_emu.features.DocumentProvider
import dev.lemon.lemon_emu.features.fetcher.SpacingItemDecoration
import dev.lemon.lemon_emu.features.settings.SettingsCategories
import dev.lemon.lemon_emu.features.settings.SettingsSearchIndex
import dev.lemon.lemon_emu.features.settings.model.Settings
import dev.lemon.lemon_emu.features.settings.ui.SettingsSubscreen
import dev.lemon.lemon_emu.model.DriverViewModel
import dev.lemon.lemon_emu.model.HomeSetting
import dev.lemon.lemon_emu.model.HomeViewModel
import dev.lemon.lemon_emu.ui.main.MainActivity
import dev.lemon.lemon_emu.utils.EmulatorMigration
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

    private val emulatorMigration = EmulatorMigrationFlow(this)


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
        if (UiMode.isModern(requireContext())) {
            setupModernHome()
        }
        refreshOptionsList()

        setInsets()
    }

    // The redesigned home: a searchable grid of cards drawn from the same list of options.
    private val homeOptions = mutableStateOf<List<HomeSetting>>(emptyList())

    private fun setupModernHome() {
        binding.scrollViewSettings.visibility = View.GONE
        val composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ModernHomeSettings(
                    options = homeOptions.value,
                    onOptionClick = { model ->
                        if (model.isEnabled.invoke()) {
                            model.onClick.invoke()
                        } else {
                            MessageDialogFragment.newInstance(
                                requireActivity(),
                                titleId = model.disabledTitleId,
                                descriptionId = model.disabledMessageId
                            ).show(parentFragmentManager, MessageDialogFragment.TAG)
                        }
                    },
                    onSearch = { query -> SettingsSearchIndex.search(requireContext(), query) },
                    onResultClick = { entry ->
                        // The section scrolls to the row and flashes it once it is shown.
                        SettingsSearchIndex.pendingTarget = entry.menuTag to entry.anchor
                        val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                            null,
                            entry.menuTag
                        )
                        binding.root.findNavController().navigate(action)
                    }
                )
            }
        }
        val params = ConstraintLayout.LayoutParams(0, 0).apply {
            topToBottom = binding.appbarHomeSettings.id
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }
        (binding.root as ViewGroup).addView(composeView, params)
    }

    // The settings categories (shared with the settings root), with "migrate from another
    // emulator" first while there is one to migrate from and "reset" last.
    private fun buildOptionsList(): MutableList<HomeSetting> =
        mutableListOf<HomeSetting>().apply {
            val migrationSources = EmulatorMigration.findSources(requireContext())
            if (migrationSources.isNotEmpty()) {
                add(
                    HomeSetting(
                        R.string.migrate_from_emulator,
                        R.string.migrate_from_emulator_description,
                        R.drawable.ic_import,
                        { emulatorMigration.start() },
                        details = MutableStateFlow(
                            migrationSources.joinToString(", ") { it.label }
                        )
                    )
                )
            }
            for (category in SettingsCategories.all) {
                add(
                    HomeSetting(
                        category.titleId,
                        category.descriptionId,
                        category.iconId,
                        {
                            val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                                null,
                                category.menuTag
                            )
                            binding.root.findNavController().navigate(action)
                        }
                    )
                )
            }
            add(
                HomeSetting(
                    R.string.reset_everything,
                    R.string.reset_everything_description,
                    R.drawable.ic_restore,
                    {
                        ResetSettingsDialogFragment().show(
                            parentFragmentManager,
                            ResetSettingsDialogFragment.TAG
                        )
                    },
                    isDestructive = true
                )
            )
        }

    private fun refreshOptionsList() {
        if (UiMode.isModern(requireContext())) {
            homeOptions.value = buildOptionsList()
            return
        }
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
