// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.google.android.material.transition.MaterialFadeThrough
import kotlinx.coroutines.launch
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import java.io.File
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.YuzuApplication
import dev.ironlemon.ironlemon_emulator.adapters.SetupAdapter
import dev.ironlemon.ironlemon_emulator.databinding.FragmentSetupBinding
import dev.ironlemon.ironlemon_emulator.features.settings.model.Settings
import dev.ironlemon.ironlemon_emulator.model.HomeViewModel
import dev.ironlemon.ironlemon_emulator.model.SetupCallback
import dev.ironlemon.ironlemon_emulator.model.SetupPage
import dev.ironlemon.ironlemon_emulator.model.StepState
import dev.ironlemon.ironlemon_emulator.ui.main.MainActivity
import dev.ironlemon.ironlemon_emulator.utils.DirectoryInitialization
import dev.ironlemon.ironlemon_emulator.utils.EmulatorMigration
import dev.ironlemon.ironlemon_emulator.utils.NativeConfig
import dev.ironlemon.ironlemon_emulator.utils.collect

class SetupFragment : Fragment() {
    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()

    private lateinit var mainActivity: MainActivity

    private lateinit var hasBeenWarned: BooleanArray

    private val pages = mutableListOf<SetupPage>()

    // Back and Next show from the second page on; Next hides on the last one.
    private var backVisible by mutableStateOf(false)
    private var nextVisible by mutableStateOf(false)

    companion object {
        const val KEY_NEXT_VISIBILITY = "NextButtonVisibility"
        const val KEY_BACK_VISIBILITY = "BackButtonVisibility"
        const val KEY_HAS_BEEN_WARNED = "HasBeenWarned"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exitTransition = MaterialFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainActivity = requireActivity() as MainActivity

        homeViewModel.setNavigationVisibility(visible = false, animated = false)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.viewPager2.currentItem > 0) {
                        pageBackward()
                    } else {
                        requireActivity().finish()
                    }
                }
            }
        )

        requireActivity().window.navigationBarColor =
            ContextCompat.getColor(requireContext(), android.R.color.transparent)

        pages.clear()
        pages.apply {
            add(
                SetupPage(
                    R.drawable.ic_yuzu_title,
                    R.string.welcome,
                    R.string.welcome_description,
                    0,
                    true,
                    R.string.get_started,
                    { pageForward() },
                    false
                )
            )

            // Coming from Eden, Citron, yuzu or Lemon: one step brings saves, keys and firmware
            // over, so it is offered first. Only shown when such an emulator is installed.
            if (EmulatorMigration.findSources(requireContext()).isNotEmpty()) {
                add(
                    SetupPage(
                        R.drawable.ic_import,
                        R.string.migrate_from_emulator,
                        R.string.migrate_from_emulator_setup_description,
                        R.drawable.ic_import,
                        true,
                        R.string.migrate_from_emulator,
                        {
                            migrationCallback = it
                            emulatorMigration.start()
                        },
                        false,
                        stepCompleted = {
                            if (migrationDone) StepState.COMPLETE else StepState.UNDEFINED
                        }
                    )
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    SetupPage(
                        R.drawable.ic_notification,
                        R.string.notifications,
                        R.string.notifications_description,
                        0,
                        false,
                        R.string.give_permission,
                        {
                            notificationCallback = it
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        true,
                        R.string.notification_warning,
                        R.string.notification_warning_description,
                        0,
                        {
                            if (NotificationManagerCompat.from(requireContext())
                                .areNotificationsEnabled()
                            ) {
                                StepState.COMPLETE
                            } else {
                                StepState.INCOMPLETE
                            }
                        }
                    )
                )
            }

            add(
                SetupPage(
                    R.drawable.ic_key,
                    R.string.keys,
                    R.string.keys_description,
                    R.drawable.ic_add,
                    true,
                    R.string.select_keys,
                    {
                        keyCallback = it
                        getProdKey.launch(arrayOf("*/*"))
                    },
                    true,
                    R.string.install_prod_keys_warning,
                    R.string.install_prod_keys_warning_description,
                    R.string.install_prod_keys_warning_help,
                    {
                        val file = File(DirectoryInitialization.userDirectory + "/keys/prod.keys")
                        if (file.exists() && NativeLibrary.areKeysPresent()) {
                            StepState.COMPLETE
                        } else {
                            StepState.INCOMPLETE
                        }
                    }
                )
            )
            add(
                SetupPage(
                    R.drawable.ic_firmware,
                    R.string.firmware,
                    R.string.firmware_setup_description,
                    R.drawable.ic_add,
                    true,
                    R.string.install_firmware,
                    {
                        firmwareCallback = it
                        mainActivity.getFirmware.launch(arrayOf("application/zip"))
                    },
                    true,
                    R.string.firmware_setup_warning,
                    R.string.firmware_setup_warning_description,
                    R.string.install_prod_keys_warning_help,
                    {
                        if (NativeLibrary.isFirmwareAvailable()) {
                            StepState.COMPLETE
                        } else {
                            StepState.INCOMPLETE
                        }
                    }
                )
            )
            add(
                SetupPage(
                    R.drawable.ic_controller,
                    R.string.games,
                    R.string.games_description,
                    R.drawable.ic_add,
                    true,
                    R.string.add_games,
                    {
                        gamesDirCallback = it
                        getGamesDirectory.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
                    },
                    true,
                    R.string.add_games_warning,
                    R.string.add_games_warning_description,
                    R.string.add_games_warning_help,
                    {
                        if (NativeConfig.getGameDirs().isNotEmpty()) {
                            StepState.COMPLETE
                        } else {
                            StepState.INCOMPLETE
                        }
                    }
                )
            )
            add(
                SetupPage(
                    R.drawable.ic_check,
                    R.string.done,
                    R.string.done_description,
                    R.drawable.ic_arrow_forward,
                    false,
                    R.string.text_continue,
                    { finishSetup() },
                    false
                )
            )
        }

        homeViewModel.shouldPageForward.collect(
            viewLifecycleOwner,
            resetState = { homeViewModel.setShouldPageForward(false) }
        ) { if (it) pageForward() }
        // MainActivity raises this after installing firmware.
        homeViewModel.firmwareInstalled.collect(
            viewLifecycleOwner,
            resetState = { homeViewModel.setFirmwareInstalled(false) }
        ) {
            if (it && ::firmwareCallback.isInitialized && NativeLibrary.isFirmwareAvailable()) {
                firmwareCallback.onStepCompleted()
            }
        }
        homeViewModel.gamesDirSelected.collect(
            viewLifecycleOwner,
            resetState = { homeViewModel.setGamesDirSelected(false) }
        ) { if (it) gamesDirCallback.onStepCompleted() }

        binding.viewPager2.apply {
            adapter = SetupAdapter(requireActivity() as AppCompatActivity, pages)
            offscreenPageLimit = 2
            isUserInputEnabled = false
        }

        binding.viewPager2.registerOnPageChangeCallback(object : OnPageChangeCallback() {
            var previousPosition: Int = 0

            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Pages are built ahead of time: show what the earlier steps (or a migration) left.
                binding.viewPager2.adapter?.notifyItemChanged(position)

                if (position == 1 && previousPosition == 0) {
                    nextVisible = true
                    backVisible = true
                } else if (position == 0 && previousPosition == 1) {
                    backVisible = false
                    nextVisible = false
                } else if (position == pages.size - 1 && previousPosition == pages.size - 2) {
                    nextVisible = false
                } else if (position == pages.size - 2 && previousPosition == pages.size - 1) {
                    nextVisible = true
                }

                previousPosition = position
            }
        })

        binding.constraintButtons.setContent {
            SetupButtons(
                backVisible = backVisible,
                nextVisible = nextVisible,
                onBack = { pageBackward() },
                onNext = ::onNextClicked
            )
        }
        restoreState(savedInstanceState)
    }

    private fun onNextClicked() {
        run {
            val index = binding.viewPager2.currentItem
            val currentPage = pages[index]

            // Checks if the user has completed the task on the current page
            if (currentPage.hasWarning) {
                val stepState = currentPage.stepCompleted.invoke()
                if (stepState != StepState.INCOMPLETE) {
                    pageForward()
                    return@run
                }

                if (!hasBeenWarned[index]) {
                    SetupWarningDialogFragment.newInstance(
                        currentPage.warningTitleId,
                        currentPage.warningDescriptionId,
                        currentPage.warningHelpLinkId,
                        index
                    ).show(childFragmentManager, SetupWarningDialogFragment.TAG)
                    return@run
                }
            }
            pageForward()
        }
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            val nextIsVisible = savedInstanceState.getBoolean(KEY_NEXT_VISIBILITY)
            val backIsVisible = savedInstanceState.getBoolean(KEY_BACK_VISIBILITY)
            hasBeenWarned = savedInstanceState.getBooleanArray(KEY_HAS_BEEN_WARNED)!!

            nextVisible = nextIsVisible
            backVisible = backIsVisible
        } else {
            hasBeenWarned = BooleanArray(pages.size)
        }

        setInsets()
    }

    override fun onStop() {
        super.onStop()
        NativeConfig.saveGlobalConfig()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding != null) {
            outState.putBoolean(KEY_NEXT_VISIBILITY, nextVisible)
            outState.putBoolean(KEY_BACK_VISIBILITY, backVisible)
        }
        outState.putBooleanArray(KEY_HAS_BEEN_WARNED, hasBeenWarned)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private var migrationDone = false

    private lateinit var migrationCallback: SetupCallback

    private val emulatorMigration = EmulatorMigrationFlow(this) {
        migrationDone = true
        if (::migrationCallback.isInitialized) {
            migrationCallback.onStepCompleted()
        }
    }

    private lateinit var notificationCallback: SetupCallback

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) {
                notificationCallback.onStepCompleted()
            }

            if (!it &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                PermissionDeniedDialogFragment().show(
                    childFragmentManager,
                    PermissionDeniedDialogFragment.TAG
                )
            }
        }

    private lateinit var keyCallback: SetupCallback

    val getProdKey =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            if (result != null) {
                mainActivity.processKey(result)
                if (NativeLibrary.areKeysPresent()) {
                    keyCallback.onStepCompleted()
                }
            }
        }

    private lateinit var gamesDirCallback: SetupCallback

    private lateinit var firmwareCallback: SetupCallback

    val getGamesDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { result ->
            if (result != null) {
                mainActivity.processGamesDir(result)
            }
        }

    private fun finishSetup() {
        PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext).edit()
            .putBoolean(Settings.PREF_FIRST_APP_LAUNCH, false)
            .apply()
        mainActivity.finishSetup(binding.root.findNavController())
    }

    fun pageForward() {
        if (_binding != null) {
            binding.viewPager2.currentItem += 1
        }
    }

    fun pageBackward() {
        if (_binding != null) {
            binding.viewPager2.currentItem -= 1
        }
    }

    fun setPageWarned(page: Int) {
        hasBeenWarned[page] = true
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val leftPadding = barInsets.left + cutoutInsets.left
            val topPadding = barInsets.top + cutoutInsets.top
            val rightPadding = barInsets.right + cutoutInsets.right
            val bottomPadding = barInsets.bottom + cutoutInsets.bottom

            if (resources.getBoolean(R.bool.small_layout)) {
                binding.viewPager2
                    .updatePadding(left = leftPadding, top = topPadding, right = rightPadding)
                binding.constraintButtons
                    .updatePadding(left = leftPadding, right = rightPadding, bottom = bottomPadding)
            } else {
                binding.viewPager2.updatePadding(top = topPadding, bottom = bottomPadding)
                binding.constraintButtons
                    .updatePadding(
                        left = leftPadding,
                        right = rightPadding,
                        bottom = bottomPadding
                    )
            }
            windowInsets
        }
}

@Composable
private fun SetupButtons(
    backVisible: Boolean,
    nextVisible: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        AnimatedVisibility(
            visible = backVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            IronLemonButton(
                text = stringResource(R.string.back),
                iconRes = R.drawable.ic_back,
                onClick = onBack
            )
        }
        AnimatedVisibility(
            visible = nextVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            IronLemonButton(
                text = stringResource(R.string.next),
                iconRes = R.drawable.ic_arrow_forward,
                primary = true,
                onClick = onNext
            )
        }
    }
}
