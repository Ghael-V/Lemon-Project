// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText
import dev.ironlemon.ironlemon_emulator.ui.modern.QuickSaveSlotInfo
import dev.ironlemon.ironlemon_emulator.ui.modern.QuickSaveSlots
import dev.ironlemon.ironlemon_emulator.ui.modern.LoadingSnapshot
import dev.ironlemon.ironlemon_emulator.ui.modern.ModernLoadingScreen
import androidx.core.view.isVisible
import dev.ironlemon.ironlemon_emulator.ui.modern.lemonInteractive
import java.nio.ByteBuffer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonColors
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonSlider
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonSwitchRow
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonType
import dev.ironlemon.ironlemon_emulator.ui.modern.showIronLemonDialog
import java.text.NumberFormat
import dev.ironlemon.ironlemon_emulator.overlay.model.OverlayControlData
import dev.ironlemon.ironlemon_emulator.nextendo.NextendoAccount
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonPopupMenu
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.ironlemon.ironlemon_emulator.ui.modern.InGameMenuPanel
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.ironlemon.ironlemon_emulator.ui.main.MainActivity
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Rational
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import androidx.activity.OnBackPressedCallback
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.drawerlayout.widget.DrawerLayout.DrawerListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import androidx.navigation.fragment.navArgs
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import dev.ironlemon.ironlemon_emulator.HomeNavigationDirections
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.activities.EmulationActivity
import dev.ironlemon.ironlemon_emulator.databinding.FragmentEmulationBinding
import dev.ironlemon.ironlemon_emulator.features.settings.model.BooleanSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.IntSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.Settings
import dev.ironlemon.ironlemon_emulator.features.settings.model.Settings.EmulationOrientation
import dev.ironlemon.ironlemon_emulator.features.settings.model.Settings.EmulationVerticalAlignment
import dev.ironlemon.ironlemon_emulator.features.settings.utils.SettingsFile
import dev.ironlemon.ironlemon_emulator.model.DriverViewModel
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.model.EmulationViewModel
import dev.ironlemon.ironlemon_emulator.overlay.model.OverlayControl
import dev.ironlemon.ironlemon_emulator.overlay.model.OverlayLayout
import dev.ironlemon.ironlemon_emulator.utils.GameStatsUtils
import dev.ironlemon.ironlemon_emulator.utils.*
import dev.ironlemon.ironlemon_emulator.utils.ViewUtils.setVisible
import java.lang.NullPointerException

class EmulationFragment : Fragment(), SurfaceHolder.Callback {
    private lateinit var emulationState: EmulationState
    private var emulationActivity: EmulationActivity? = null
    private var perfStatsUpdater: (() -> Unit)? = null
    private var thermalStatsUpdater: (() -> Unit)? = null

    private var _binding: FragmentEmulationBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<EmulationFragmentArgs>()

    private lateinit var game: Game

    private val emulationViewModel: EmulationViewModel by activityViewModels()
    private val driverViewModel: DriverViewModel by activityViewModels()

    private var isInFoldableLayout = false

    // One Quick Save/Load at a time: each runs on its own thread and can take seconds.
    private val quickStateRunning = AtomicBoolean(false)

    // The Quick Save/Load message on screen. Android shows toasts one after another, so a new one
    // replaces it rather than waiting behind it (which brought a quick second load's messages up
    // seconds late, in the middle of play).
    private var quickStateToast: Toast? = null
    private val quickStateToastHandler = Handler(Looper.getMainLooper())

    private lateinit var powerManager: PowerManager
    private var adaptiveMonitor: AdaptivePerformance.Monitor? = null
    private var lastOverlaySpeed = 1.0

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is EmulationActivity) {
            emulationActivity = context
            NativeLibrary.setEmulationActivity(context)
        } else {
            throw IllegalStateException("EmulationFragment must have EmulationActivity parent")
        }
    }

    /**
     * Initialize anything that doesn't depend on the layout / views in here.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateOrientation()

        powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager

        val intentUri: Uri? = requireActivity().intent.data
        var intentGame: Game? = null
        if (intentUri != null) {
            intentGame = if (Game.extensions.contains(FileUtil.getExtension(intentUri))) {
                GameHelper.getGame(requireActivity().intent.data!!, false)
            } else {
                null
            }
        }

        try {
            game = if (args.game != null) {
                args.game!!
            } else {
                intentGame!!
            }
        } catch (e: NullPointerException) {
            Toast.makeText(
                requireContext(),
                R.string.no_game_present,
                Toast.LENGTH_SHORT
            ).show()
            requireActivity().finish()
            return
        }

        // A past session asked for the Battery preset (adaptive performance): write it before
        // the config is loaded for this boot.
        AdaptivePerformance.applyPending(requireContext(), game)

        // Always load custom settings when launching a game from an intent
        if (args.custom || intentGame != null) {
            SettingsFile.loadCustomConfig(game)
            NativeConfig.unloadPerGameConfig()
        } else {
            NativeConfig.reloadGlobalConfig()
        }

        // Install the selected driver asynchronously as the game starts
        driverViewModel.onLaunchGame()

        // So this fragment doesn't restart on configuration changes; i.e. rotation.
        retainInstance = true
        emulationState = EmulationState(game.path) {
            return@EmulationState driverViewModel.isInteractionAllowed.value
        }
    }

    /**
     * Initialize the UI and start emulation in here.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEmulationBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (requireActivity().isFinishing) {
            return
        }

        binding.surfaceEmulation.holder.addCallback(this)
        binding.doneControlConfig.setOnClickListener { stopConfiguringControls() }

        binding.drawerLayout.addDrawerListener(object : DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                binding.surfaceInputOverlay.dispatchTouchEvent(
                    MotionEvent.obtain(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis() + 100,
                        MotionEvent.ACTION_UP,
                        0f,
                        0f,
                        0
                    )
                )
            }

            override fun onDrawerOpened(drawerView: View) {
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                binding.inGameMenu.requestFocus()
                // The panel re-reads the menu (titles change while playing) and takes focus.
                inGameMenuTick.intValue++
                emulationViewModel.setDrawerOpen(true)
            }

            override fun onDrawerClosed(drawerView: View) {
                binding.drawerLayout.setDrawerLockMode(IntSetting.LOCK_DRAWER.getInt())
                emulationViewModel.setDrawerOpen(false)
            }

            override fun onDrawerStateChanged(newState: Int) {
                // No op
            }
        })
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.inGameMenu.getHeaderView(0).findViewById<TextView>(R.id.text_game_title).text =
            game.title

        binding.inGameMenu.menu.findItem(R.id.menu_lock_drawer).apply {
            val lockMode = IntSetting.LOCK_DRAWER.getInt()
            val titleId = if (lockMode == DrawerLayout.LOCK_MODE_LOCKED_CLOSED) {
                R.string.unlock_drawer
            } else {
                R.string.lock_drawer
            }
            val iconId = if (lockMode == DrawerLayout.LOCK_MODE_UNLOCKED) {
                R.drawable.ic_unlock
            } else {
                R.drawable.ic_lock
            }

            title = getString(titleId)
            icon = ResourcesCompat.getDrawable(
                resources,
                iconId,
                requireContext().theme
            )
        }

        // The macro panel's "Show on-screen controller": as the overlay options' Show overlay does.
        binding.macroOverlay.onShowOverlayRequested = {
            BooleanSetting.SHOW_INPUT_OVERLAY.setBoolean(true)
            binding.surfaceInputOverlay.refreshControls()
            binding.surfaceInputOverlay.setVisible(emulationViewModel.emulationStarted.value)
        }

        binding.inGameMenu.setNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.menu_pause_emulation -> {
                    togglePauseEmulation()
                    binding.inGameMenu.requestFocus()
                    true
                }

                R.id.menu_quick_save_state -> {
                    quickSaveState()
                    binding.inGameMenu.requestFocus()
                    true
                }

                R.id.menu_quick_load_state -> {
                    quickLoadState()
                    binding.inGameMenu.requestFocus()
                    true
                }

                R.id.menu_load_slot -> {
                    showLoadSlotDialog()
                    true
                }

                R.id.menu_lemon_cheater -> {
                    binding.cheatOverlay.visibility =
                        if (binding.cheatOverlay.visibility == View.VISIBLE) {
                            View.GONE
                        } else {
                            View.VISIBLE
                        }
                    binding.inGameMenu.requestFocus()
                    true
                }

                R.id.menu_cheats -> {
                    game?.let {
                        CheatsDialogFragment.newInstance(it)
                            .show(childFragmentManager, CheatsDialogFragment.TAG)
                    }
                    true
                }

                R.id.menu_lemon_macro -> {
                    binding.macroOverlay.visibility =
                        if (binding.macroOverlay.visibility == View.VISIBLE) {
                            View.GONE
                        } else {
                            View.VISIBLE
                        }
                    binding.inGameMenu.requestFocus()
                    true
                }

                R.id.menu_settings -> {
                    val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                        null,
                        Settings.MenuTag.SECTION_ROOT
                    )
                    binding.inGameMenu.requestFocus()
                    binding.root.findNavController().navigate(action)
                    true
                }

                R.id.menu_settings_per_game -> {
                    val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                        args.game,
                        Settings.MenuTag.SECTION_ROOT
                    )
                    binding.inGameMenu.requestFocus()
                    binding.root.findNavController().navigate(action)
                    true
                }

                R.id.menu_controls -> {
                    val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                        null,
                        Settings.MenuTag.SECTION_INPUT
                    )
                    binding.root.findNavController().navigate(action)
                    true
                }

                R.id.menu_overlay_controls -> {
                    showOverlayOptions()
                    true
                }

                R.id.menu_lock_drawer -> {
                    when (IntSetting.LOCK_DRAWER.getInt()) {
                        DrawerLayout.LOCK_MODE_UNLOCKED -> {
                            IntSetting.LOCK_DRAWER.setInt(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                            it.title = resources.getString(R.string.unlock_drawer)
                            it.icon = ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_lock,
                                requireContext().theme
                            )
                        }

                        DrawerLayout.LOCK_MODE_LOCKED_CLOSED -> {
                            IntSetting.LOCK_DRAWER.setInt(DrawerLayout.LOCK_MODE_UNLOCKED)
                            it.title = resources.getString(R.string.lock_drawer)
                            it.icon = ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_unlock,
                                requireContext().theme
                            )
                        }
                    }
                    binding.inGameMenu.requestFocus()
                    NativeConfig.saveGlobalConfig()
                    true
                }

                R.id.menu_exit -> {
                    exitEmulation()
                    binding.inGameMenu.requestFocus()
                    true
                }

                else -> true
            }
        }
        showInGameMenuPanel()
        showModernLoadingScreen()

        setInsets()

        requireActivity().onBackPressedDispatcher.addCallback(
            requireActivity(),
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!NativeLibrary.isRunning()) {
                        return
                    }
                    emulationViewModel.setDrawerOpen(!binding.drawerLayout.isOpen)
                }
            }
        )

        GameIconUtils.loadGameIcon(game, binding.loadingImage)
        binding.loadingTitle.text = game.title
        binding.loadingTitle.isSelected = true
        binding.loadingText.isSelected = true

        WindowInfoTracker.getOrCreate(requireContext())
            .windowLayoutInfo(requireActivity()).collect(viewLifecycleOwner) {
                updateFoldableLayout(requireActivity() as EmulationActivity, it)
            }
        emulationViewModel.shaderProgress.collect(viewLifecycleOwner) {
            if (it > 0 && it != emulationViewModel.totalShaders.value) {
                binding.loadingProgressIndicator.isIndeterminate = false

                if (it < binding.loadingProgressIndicator.max) {
                    binding.loadingProgressIndicator.progress = it
                }
            }

            if (it == emulationViewModel.totalShaders.value) {
                binding.loadingText.setText(R.string.loading)
                binding.loadingProgressIndicator.isIndeterminate = true
            }
        }
        emulationViewModel.totalShaders.collect(viewLifecycleOwner) {
            binding.loadingProgressIndicator.max = it
        }
        emulationViewModel.shaderMessage.collect(viewLifecycleOwner) {
            if (it.isNotEmpty()) {
                binding.loadingText.text = it
            }
        }

        emulationViewModel.emulationStarted.collect(viewLifecycleOwner) {
            if (it) {
                binding.drawerLayout.setDrawerLockMode(IntSetting.LOCK_DRAWER.getInt())
                ViewUtils.showView(binding.surfaceInputOverlay)
                // The core is up, but the game has not drawn anything yet: the loading screen stays
                // until its first frame (a cold shader cache can take a while).
                startFirstFrameWatcher()

                emulationState.updateSurface()

                // Setup overlays
                updateShowFpsOverlay()
                updateThermalOverlay()
                startAdaptivePerformance()
            }
        }
        emulationViewModel.isEmulationStopping.collect(viewLifecycleOwner) {
            if (it) {
                binding.loadingText.setText(R.string.shutting_down)
                ViewUtils.showView(binding.loadingIndicator)
                ViewUtils.hideView(binding.inputContainer)
                ViewUtils.hideView(binding.showFpsText)
            }
        }
        emulationViewModel.drawerOpen.collect(viewLifecycleOwner) {
            if (it) {
                binding.drawerLayout.open()
                binding.inGameMenu.requestFocus()
            } else {
                binding.drawerLayout.close()
            }
        }
        emulationViewModel.programChanged.collect(viewLifecycleOwner) {
            if (it != 0) {
                emulationViewModel.setEmulationStarted(false)
                binding.drawerLayout.close()
                binding.drawerLayout
                    .setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                ViewUtils.hideView(binding.surfaceInputOverlay)
                ViewUtils.showView(binding.loadingIndicator)
            }
        }
        emulationViewModel.emulationStopped.collect(viewLifecycleOwner) {
            if (it && emulationViewModel.programChanged.value != -1) {
                if (perfStatsUpdater != null) {
                    perfStatsUpdateHandler.removeCallbacks(perfStatsUpdater!!)
                }
                emulationState.changeProgram(emulationViewModel.programChanged.value)
                emulationViewModel.setProgramChanged(-1)
                emulationViewModel.setEmulationStopped(false)
            }
        }

        driverViewModel.isInteractionAllowed.collect(viewLifecycleOwner) {
            if (it) startEmulation()
        }
    }

    private fun startEmulation(programIndex: Int = 0) {
        if (!NativeLibrary.isRunning() && !NativeLibrary.isPaused()) {
            if (!DirectoryInitialization.areDirectoriesReady) {
                DirectoryInitialization.start()
            }

            updateScreenLayout()

            emulationState.run(emulationActivity!!.isActivityRecreated, programIndex)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding == null) {
            return
        }

        updateScreenLayout()
        val showInputOverlay = BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()
        if (emulationActivity?.isInPictureInPictureMode == true) {
            if (binding.drawerLayout.isOpen) {
                binding.drawerLayout.close()
            }
            if (showInputOverlay) {
                binding.surfaceInputOverlay.setVisible(visible = false, gone = false)
            }
        } else {
            binding.surfaceInputOverlay.setVisible(
                showInputOverlay && emulationViewModel.emulationStarted.value
            )
            if (!isInFoldableLayout) {
                if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                    binding.surfaceInputOverlay.layout = OverlayLayout.Portrait
                } else {
                    binding.surfaceInputOverlay.layout = OverlayLayout.Landscape
                }
            }
        }
    }

    private val inGameMenuTick = mutableIntStateOf(0)

    /**
     * Iron Lemon's in-game panel (Compose), drawn over the classic drawer menu. It shows that same
     * menu, and a tap goes through the menu's own handler, so every option behaves as before.
     */
    /**
     * The full-screen loading screen, drawn over the classic loading card: it shows while the card
     * is shown, and reads its message and progress from it, so the loading logic stays as it is.
     */
    private fun showModernLoadingScreen() {
        val card = binding.loadingIndicator
        val parent = card.parent as? ViewGroup ?: return
        val screen = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ModernLoadingScreen(
                    isActive = { _binding?.loadingIndicator?.isVisible == true },
                    read = ::loadingSnapshot
                )
            }
            isClickable = true // nothing behind it is usable while it shows
        }
        parent.addView(
            screen,
            parent.indexOfChild(card) + 1,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // Follow the card, which the loading logic shows and hides (with a fade).
        card.viewTreeObserver.addOnPreDrawListener {
            val shown = card.isVisible && card.alpha > 0f
            screen.visibility = if (shown) View.VISIBLE else View.GONE
            screen.alpha = card.alpha
            true
        }
    }

    private fun loadingSnapshot(): LoadingSnapshot {
        val b = _binding ?: return LoadingSnapshot(game, "", 0, 0, true)
        val bar = b.loadingProgressIndicator
        return LoadingSnapshot(
            game = game,
            message = b.loadingText.text?.toString().orEmpty(),
            progress = bar.progress,
            max = bar.max,
            indeterminate = bar.isIndeterminate
        )
    }

    // Polls for the game's first frame on a handler of its own, so nothing else that clears
    // handler callbacks can cancel it and leave the loading screen up.
    private val firstFrameHandler = Handler(Looper.getMainLooper())
    private var firstFrameWatcher: Runnable? = null

    /** Hides the loading screen once the game is presenting frames, or after a minute. */
    private fun startFirstFrameWatcher() {
        stopFirstFrameWatcher()
        val started = SystemClock.elapsedRealtime()
        val watcher = object : Runnable {
            override fun run() {
                val b = _binding
                if (b == null || emulationViewModel.isEmulationStopping.value) {
                    firstFrameWatcher = null
                    return
                }
                // The first frame is often a black one before the game draws anything: wait until
                // it has presented a few.
                val frames = NativeLibrary.getPresentedFrameCount()
                if (frames >= FIRST_FRAMES_SHOWN ||
                    SystemClock.elapsedRealtime() - started > FIRST_FRAME_TIMEOUT_MS
                ) {
                    firstFrameWatcher = null
                    ViewUtils.hideView(b.loadingIndicator)
                    return
                }
                firstFrameHandler.postDelayed(this, FIRST_FRAME_POLL_MS)
            }
        }
        firstFrameWatcher = watcher
        firstFrameHandler.post(watcher)
    }

    private fun startAdaptivePerformance() {
        if (adaptiveMonitor != null) {
            return
        }
        adaptiveMonitor = AdaptivePerformance.Monitor(
            context = requireContext().applicationContext,
            isActive = {
                _binding != null && emulationViewModel.emulationStarted.value &&
                    !emulationViewModel.isEmulationStopping.value && emulationState.isRunning &&
                    firstFrameWatcher == null
            },
            // The stats reset on every read: while the overlay shows them, share its reading.
            readSpeed = {
                if (BooleanSetting.SHOW_PERFORMANCE_OVERLAY.getBoolean()) {
                    lastOverlaySpeed
                } else {
                    NativeLibrary.getPerfStats().getOrElse(3) { 1.0 }
                }
            },
            onTrigger = ::onAdaptivePerformanceTriggered
        ).also { it.start() }
    }

    private fun onAdaptivePerformanceTriggered(reason: AdaptivePerformance.Reason) {
        val context = context ?: return
        if (!::game.isInitialized) {
            return
        }
        AdaptivePerformance.markPending(context, game)
        val text = getString(
            when (reason) {
                AdaptivePerformance.Reason.THERMAL -> R.string.adaptive_performance_thermal
                AdaptivePerformance.Reason.SPEED -> R.string.adaptive_performance_speed
            },
            game.title
        )
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        postAdaptivePerformanceNotification(context, text)
    }

    private fun postAdaptivePerformanceNotification(context: Context, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(
            context,
            getString(R.string.notice_notification_channel_id)
        )
            .setSmallIcon(R.drawable.ic_stat_notification_logo)
            .setContentTitle(getString(R.string.adaptive_performance_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ADAPTIVE_PERFORMANCE_NOTIFICATION_ID, notification)
    }

    private fun stopFirstFrameWatcher() {
        firstFrameWatcher?.let { firstFrameHandler.removeCallbacks(it) }
        firstFrameWatcher = null
    }

    private fun showInGameMenuPanel() {
        val menu = binding.inGameMenu.menu
        val panel = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                InGameMenuPanel(
                    menu = menu,
                    game = game,
                    tick = inGameMenuTick.intValue,
                    performance = null,
                    saves = QuickSaveSlots(read = ::quickSaveSlotInfos, onSlot = ::onQuickSaveSlot),
                    onItem = { id -> menu.performIdentifierAction(id, 0) }
                )
            }
        }
        // The classic list stays (its items anchor popups) but is no longer seen.
        for (i in 0 until binding.inGameMenu.childCount) {
            binding.inGameMenu.getChildAt(i).visibility = View.INVISIBLE
        }
        binding.inGameMenu.addView(
            panel,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        binding.inGameMenu.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    override fun onResume() {
        super.onResume()
        // Play time counts while the game is on screen (shown in the library).
        if (::game.isInitialized) {
            GameStatsUtils.startSession(game)
        }
    }

    override fun onPause() {
        GameStatsUtils.endSession(requireContext())
        if (emulationState.isRunning && emulationActivity?.isInPictureInPictureMode != true) {
            emulationState.pause()
        }
        super.onPause()
    }

    private fun quickLoadMessage(result: Int): Int = when (result) {
        NativeLibrary.QUICK_LOAD_LOADED -> R.string.emulation_quick_load_state_success
        NativeLibrary.QUICK_LOAD_INCOMPATIBLE -> R.string.emulation_quick_load_state_incompatible
        NativeLibrary.QUICK_LOAD_NO_SAFE_MOMENT -> R.string.emulation_quick_state_no_safe_moment
        NativeLibrary.QUICK_LOAD_GAME_LOADING -> R.string.emulation_quick_load_state_game_loading
        NativeLibrary.QUICK_LOAD_NOT_THIS_SESSION -> R.string.emulation_quick_load_state_not_this_session
        NativeLibrary.QUICK_LOAD_MOVED_PAST -> R.string.emulation_quick_load_state_moved_past
        else -> R.string.emulation_quick_load_state_failure
    }

    /**
     * Writes the picture the load list shows for the slot just saved: the game's frame at the
     * save, scaled down to a small JPEG next to the save. Runs on the Quick Save's own thread.
     */
    private fun saveQuickSaveThumbnail() {
        val pixels = NativeLibrary.takeQuickSaveThumbnail()
        val width = NativeLibrary.QUICK_SAVE_PICTURE_WIDTH
        val height = NativeLibrary.QUICK_SAVE_PICTURE_HEIGHT
        val slots = NativeLibrary.getQuickSaveSlots()
        if (pixels.size < width * height * 4 || slots.size < 3) {
            Log.warning("[QuickState] no picture for the new slot (${pixels.size} bytes)")
            return
        }
        val id = slots[slots.size - 3]
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        frame.copyPixelsFromBuffer(ByteBuffer.wrap(pixels, 0, width * height * 4))
        val thumbnail = Bitmap.createScaledBitmap(
            frame,
            QUICK_SAVE_THUMBNAIL_WIDTH,
            QUICK_SAVE_THUMBNAIL_WIDTH * height / width,
            true
        )
        frame.recycle()
        try {
            File(NativeLibrary.quickSaveThumbnailPath(id)).outputStream().use {
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, it)
            }
        } catch (e: Exception) {
            Log.warning("[QuickState] could not write the picture of slot $id: ${e.message}")
        }
        thumbnail.recycle()
    }

    /** One quicksave slot as getQuickSaveSlots gives it, with how many slots are newer. */
    private class QuickSaveSlotRow(
        val id: Long,
        val savedAtMs: Long,
        val loadable: Boolean,
        val newer: Int,
        val picture: ImageBitmap?
    )

    // Each slot's picture, read from its file once.
    private val slotPictures = mutableMapOf<Long, ImageBitmap?>()

    private fun slotPicture(id: Long): ImageBitmap? = slotPictures.getOrPut(id) {
        BitmapFactory.decodeFile(NativeLibrary.quickSaveThumbnailPath(id))?.asImageBitmap()
    }

    /** The quick saves of this session, newest first. */
    private fun quickSaveSlotRows(): List<QuickSaveSlotRow> {
        val flat = NativeLibrary.getQuickSaveSlots()
        val count = flat.size / 3
        // A picture is written just after its save: read it again until it is there.
        slotPictures.entries.removeAll { it.value == null }
        return (count - 1 downTo 0).map {
            val id = flat[it * 3]
            QuickSaveSlotRow(
                id = id,
                savedAtMs = flat[it * 3 + 1],
                loadable = flat[it * 3 + 2] != 0L,
                newer = count - 1 - it,
                picture = slotPicture(id)
            )
        }
    }

    private fun quickSaveSlotInfos(): List<QuickSaveSlotInfo> {
        if (!isAdded) return emptyList()
        return quickSaveSlotRows().map { row ->
            QuickSaveSlotInfo(
                id = row.id,
                title = savedWhen(row.savedAtMs),
                subtitle = if (row.loadable) {
                    savedAt(row.savedAtMs)
                } else {
                    getString(R.string.emulation_load_slot_gone)
                },
                picture = row.picture,
                loadable = row.loadable,
                newest = row.newer == 0
            )
        }
    }

    /** A slot picked in the menu: the newest loads at once, an older one asks first. */
    private fun onQuickSaveSlot(slot: QuickSaveSlotInfo) {
        val row = quickSaveSlotRows().firstOrNull { it.id == slot.id } ?: return
        if (!row.loadable) return
        if (row.newer == 0) {
            loadSlot(row.id)
        } else {
            showLoadSlotDialog(confirm = row)
        }
    }

    /** When a save was made, for a slot's title: "Just now", "12 minutes ago", "Yesterday". */
    private fun savedWhen(savedAtMs: Long): String {
        val now = System.currentTimeMillis()
        return when {
            !DateUtils.isToday(savedAtMs) ->
                DateUtils.getRelativeTimeSpanString(savedAtMs, now, DateUtils.DAY_IN_MILLIS).toString()
            now - savedAtMs < 10 * DateUtils.SECOND_IN_MILLIS ->
                getString(R.string.emulation_load_slot_just_now)
            else -> DateUtils.getRelativeTimeSpanString(savedAtMs, now, DateUtils.SECOND_IN_MILLIS)
                .toString()
        }
    }

    /** The clock time of a save, with seconds, as the language writes it. */
    private fun savedTime(savedAtMs: Long): String = DateFormat.format(
        DateFormat.getBestDateTimePattern(resources.configuration.locales[0], "jmmss"),
        savedAtMs
    ).toString()

    /** The date of a save, with its weekday if [withWeekday]. */
    private fun savedDate(savedAtMs: Long, withWeekday: Boolean): String = DateFormat.format(
        DateFormat.getBestDateTimePattern(
            resources.configuration.locales[0],
            if (withWeekday) "EEEMMMd" else "MMMd"
        ),
        savedAtMs
    ).toString()

    /** When a save was made, for a slot's second line: its time, and its date if not today. */
    private fun savedAt(savedAtMs: Long): String = if (DateUtils.isToday(savedAtMs)) {
        savedTime(savedAtMs)
    } else {
        "${savedDate(savedAtMs, true)} · ${savedTime(savedAtMs)}"
    }

    /** For the confirmation: "12 minutes ago", "yesterday, 11:42:10 PM" or "Oct 30, 11:42:10 PM". */
    private fun savedWhenLong(savedAtMs: Long): String = when {
        DateUtils.isToday(savedAtMs) -> DateUtils.getRelativeTimeSpanString(
            savedAtMs,
            System.currentTimeMillis(),
            DateUtils.SECOND_IN_MILLIS
        ).toString()
        DateUtils.isToday(savedAtMs + DateUtils.DAY_IN_MILLIS) ->
            getString(R.string.emulation_load_slot_yesterday_at, savedTime(savedAtMs))
        else -> "${savedDate(savedAtMs, false)}, ${savedTime(savedAtMs)}"
    }

    /**
     * Lists the quicksave slots, newest first, with their pictures and when they were made, and
     * loads the one picked. Loading an older one deletes the newer ones, so that asks first.
     */
    private fun showLoadSlotDialog(confirm: QuickSaveSlotRow? = null) {
        if (!isAdded) {
            return
        }
        val flat = NativeLibrary.getQuickSaveSlots()
        val count = flat.size / 3
        if (count == 0) {
            showQuickStateToast(
                Toast.makeText(
                    requireContext().applicationContext,
                    R.string.emulation_quick_load_state_not_this_session,
                    Toast.LENGTH_SHORT
                ),
                0
            )
            return
        }
        val rows = quickSaveSlotRows()
        showIronLemonDialog(requireContext()) { dismiss ->
            var confirming by remember { mutableStateOf(confirm) }
            val pending = confirming
            if (pending == null) {
                IronLemonDialogCard(
                    title = stringResource(R.string.emulation_load_slot_title),
                    buttons = {
                        IronLemonButton(
                            text = stringResource(android.R.string.cancel),
                            height = 44.dp,
                            onClick = dismiss
                        )
                    }
                ) {
                    Column(
                        Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rows.forEach { row ->
                            SlotRow(row) {
                                if (row.newer == 0) {
                                    dismiss()
                                    loadSlot(row.id)
                                } else {
                                    confirming = row
                                }
                            }
                        }
                    }
                }
            } else {
                IronLemonDialogCard(
                    title = stringResource(
                        R.string.emulation_load_slot_confirm_title,
                        savedWhenLong(pending.savedAtMs)
                    ),
                    buttons = {
                        IronLemonButton(
                            text = stringResource(android.R.string.cancel),
                            height = 44.dp,
                            onClick = { if (confirm != null) dismiss() else confirming = null }
                        )
                        IronLemonButton(
                            text = stringResource(R.string.emulation_load_slot_load),
                            primary = true,
                            height = 44.dp,
                            onClick = {
                                dismiss()
                                loadSlot(pending.id)
                            }
                        )
                    }
                ) {
                    SlotPicture(pending.picture)
                    Spacer(Modifier.height(12.dp))
                    IronLemonDialogText(
                        pluralStringResource(
                            R.plurals.emulation_load_slot_confirm_message,
                            pending.newer,
                            pending.newer
                        )
                    )
                }
            }
        }
    }

    @Composable
    private fun SlotRow(row: QuickSaveSlotRow, onPick: () -> Unit) {
        val shape = RoundedCornerShape(18.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(if (row.loadable) 1f else 0.38f)
                .lemonInteractive(
                    shape = shape,
                    cornerRadius = 18.dp,
                    focusScale = 1.02f,
                    onClick = { if (row.loadable) onPick() }
                )
                .clip(shape)
                .background(IronLemonColors.Surface)
                .border(1.dp, IronLemonColors.Outline, shape)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlotPicture(row.picture)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                BasicText(savedWhen(row.savedAtMs), style = IronLemonType.Button)
                BasicText(
                    when {
                        !row.loadable -> stringResource(R.string.emulation_load_slot_gone)
                        row.newer == 0 -> stringResource(
                            R.string.emulation_load_slot_quick_load,
                            savedAt(row.savedAtMs)
                        )
                        else -> savedAt(row.savedAtMs)
                    },
                    style = IronLemonType.Body
                )
            }
        }
    }

    @Composable
    private fun SlotPicture(picture: ImageBitmap?) {
        val shape = RoundedCornerShape(10.dp)
        Box(
            Modifier.size(width = 128.dp, height = 72.dp).clip(shape)
                .background(IronLemonColors.SurfaceRaised)
        ) {
            if (picture != null) {
                Image(
                    bitmap = picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }

    private fun loadSlot(id: Long) {
        runQuickState(
            R.string.emulation_quick_load_state_working,
            R.string.emulation_quick_load_state_still_working
        ) {
            quickLoadMessage(NativeLibrary.quickLoadSlot(id))
        }
    }

    // Run from the in-game menu or a gamepad hotkey (EmulationActivity).

    fun togglePauseEmulation() {
        if (!this::emulationState.isInitialized || _binding == null) {
            return
        }
        val item = binding.inGameMenu.menu.findItem(R.id.menu_pause_emulation)
        if (emulationState.isPaused) {
            emulationState.run(false)
            item?.title = resources.getString(R.string.emulation_pause)
            item?.icon =
                ResourcesCompat.getDrawable(resources, R.drawable.ic_pause, requireContext().theme)
        } else {
            emulationState.pause()
            item?.title = resources.getString(R.string.emulation_unpause)
            item?.icon =
                ResourcesCompat.getDrawable(resources, R.drawable.ic_play, requireContext().theme)
        }
    }

    fun exitEmulation() {
        if (!this::emulationState.isInitialized || _binding == null) {
            return
        }
        emulationState.stop()
        NativeConfig.reloadGlobalConfig()
        emulationViewModel.setIsEmulationStopping(true)
        binding.drawerLayout.close()
    }

    /** Returns whether the overlay is now shown. */
    fun toggleStatsOverlay(): Boolean {
        val show = !BooleanSetting.SHOW_PERFORMANCE_OVERLAY.getBoolean()
        BooleanSetting.SHOW_PERFORMANCE_OVERLAY.setBoolean(show)
        if (_binding != null) {
            updateShowFpsOverlay()
        }
        NativeConfig.saveGlobalConfig()
        return show
    }

    /** The device overlay here is the thermal one. Returns whether it is now shown. */
    fun toggleSocOverlay(): Boolean {
        val show = !BooleanSetting.SHOW_THERMAL_OVERLAY.getBoolean()
        BooleanSetting.SHOW_THERMAL_OVERLAY.setBoolean(show)
        if (_binding != null) {
            updateThermalOverlay()
        }
        NativeConfig.saveGlobalConfig()
        return show
    }

    fun quickSaveState() {
        if (!isAdded) {
            return
        }
        // It may wait a moment for a safe point to save at: not on the main thread.
        runQuickState(
            R.string.emulation_quick_save_state_working,
            R.string.emulation_quick_save_state_still_working
        ) {
            when (NativeLibrary.quickSaveState()) {
                NativeLibrary.QUICK_SAVE_SAVED -> {
                    saveQuickSaveThumbnail()
                    R.string.emulation_quick_save_state_success
                }
                NativeLibrary.QUICK_SAVE_NO_SAFE_MOMENT -> R.string.emulation_quick_state_no_safe_moment
                NativeLibrary.QUICK_SAVE_NO_SPACE -> R.string.emulation_quick_save_state_no_space
                else -> R.string.emulation_quick_save_state_failure
            }
        }
    }

    fun quickLoadState() {
        if (!isAdded) {
            return
        }
        // Restoring about a gigabyte of memory takes a moment: not on the main thread.
        runQuickState(
            R.string.emulation_quick_load_state_working,
            R.string.emulation_quick_load_state_still_working
        ) {
            quickLoadMessage(NativeLibrary.quickLoadState())
        }
    }

    /**
     * Runs a Quick Save or Quick Load off the main thread: says it started (they can take a few
     * seconds) and shows the result. A second tap while one is running is ignored, so taps do not
     * queue up one load after another.
     */
    private fun runQuickState(
        workingMessage: Int,
        stillWorkingMessage: Int,
        action: () -> Int
    ) {
        if (!quickStateRunning.compareAndSet(false, true)) {
            return
        }
        val appContext = requireContext().applicationContext
        // Shown at once and kept up until the result replaces it: a toast lasts at most 3.5 s, so a
        // longer one is followed by "… is still processing…".
        showQuickStateToast(Toast.makeText(appContext, workingMessage, Toast.LENGTH_LONG), 0)
        val stillWorking = object : Runnable {
            override fun run() {
                showQuickStateToast(
                    Toast.makeText(appContext, stillWorkingMessage, Toast.LENGTH_LONG),
                    0
                )
                quickStateToastHandler.postDelayed(this, QUICK_STATE_WORKING_SHOWN_MS)
            }
        }
        quickStateToastHandler.postDelayed(stillWorking, QUICK_STATE_WORKING_SHOWN_MS)
        Thread {
            val message = try {
                action()
            } finally {
                quickStateRunning.set(false)
            }
            activity?.runOnUiThread {
                // The game being back where it was saved says it worked: a short note is enough.
                // Other results are read in full; only the long explanations need a long toast.
                val succeeded = message == R.string.emulation_quick_save_state_success ||
                    message == R.string.emulation_quick_load_state_success
                val length = if (message == R.string.emulation_quick_load_state_incompatible ||
                    message == R.string.emulation_quick_load_state_moved_past
                ) {
                    Toast.LENGTH_LONG
                } else {
                    Toast.LENGTH_SHORT
                }
                showQuickStateToast(
                    Toast.makeText(appContext, message, length),
                    if (succeeded) QUICK_STATE_SUCCESS_SHOWN_MS else 0
                )
                // A long toast's 3.5 s is too short to read this one: it is shown once more.
                if (message == R.string.emulation_quick_load_state_moved_past) {
                    val again = Runnable {
                        showQuickStateToast(
                            Toast.makeText(appContext, message, Toast.LENGTH_LONG),
                            0
                        )
                    }
                    quickStateToastHandler.postDelayed(again, QUICK_STATE_WORKING_SHOWN_MS)
                }
            }
        }.start()
    }

    /** Puts [toast] (null: none) in place of the Quick Save/Load message, for [shownMs] if set. */
    private fun showQuickStateToast(toast: Toast?, shownMs: Long) {
        quickStateToastHandler.removeCallbacksAndMessages(null)
        quickStateToast?.cancel()
        quickStateToast = toast
        toast?.show()
        if (toast != null && shownMs > 0) {
            quickStateToastHandler.postDelayed({ toast.cancel() }, shownMs)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopFirstFrameWatcher()
        adaptiveMonitor?.stop()
        adaptiveMonitor = null
        quickStateToastHandler.removeCallbacksAndMessages(null)
        _binding = null
    }

    override fun onDetach() {
        NativeLibrary.clearEmulationActivity()
        super.onDetach()
    }

    private fun resetInputOverlay() {
        IntSetting.OVERLAY_SCALE.reset()
        IntSetting.OVERLAY_OPACITY.reset()
        binding.surfaceInputOverlay.post {
            binding.surfaceInputOverlay.resetLayoutVisibilityAndPlacement()
        }
    }

    private fun updateShowFpsOverlay() {
        val showOverlay = BooleanSetting.SHOW_PERFORMANCE_OVERLAY.getBoolean()
        binding.showFpsText.setVisible(showOverlay)
        if (showOverlay) {
            val SYSTEM_FPS = 0
            val FPS = 1
            val FRAMETIME = 2
            val SPEED = 3
            perfStatsUpdater = {
                if (emulationViewModel.emulationStarted.value &&
                    !emulationViewModel.isEmulationStopping.value
                ) {
                    val perfStats = NativeLibrary.getPerfStats()
                    lastOverlaySpeed = perfStats.getOrElse(SPEED) { 1.0 }
                    val cpuBackend = NativeLibrary.getCpuBackend()
                    val gpuDriver = NativeLibrary.getGpuDriver()
                    if (_binding != null) {
                        binding.showFpsText.text =
                            String.format("FPS: %.1f\n%s/%s", perfStats[FPS], cpuBackend, gpuDriver)
                    }
                    perfStatsUpdateHandler.postDelayed(perfStatsUpdater!!, 800)
                }
            }
            perfStatsUpdateHandler.post(perfStatsUpdater!!)
        } else {
            if (perfStatsUpdater != null) {
                perfStatsUpdateHandler.removeCallbacks(perfStatsUpdater!!)
            }
        }
    }

    private fun updateThermalOverlay() {
        val showOverlay = BooleanSetting.SHOW_THERMAL_OVERLAY.getBoolean()
        binding.showThermalsText.setVisible(showOverlay)
        if (showOverlay) {
            thermalStatsUpdater = {
                if (emulationViewModel.emulationStarted.value &&
                    !emulationViewModel.isEmulationStopping.value
                ) {
                    val thermalStatus = when (powerManager.currentThermalStatus) {
                        PowerManager.THERMAL_STATUS_LIGHT -> "😥"
                        PowerManager.THERMAL_STATUS_MODERATE -> "🥵"
                        PowerManager.THERMAL_STATUS_SEVERE -> "🔥"
                        PowerManager.THERMAL_STATUS_CRITICAL,
                        PowerManager.THERMAL_STATUS_EMERGENCY,
                        PowerManager.THERMAL_STATUS_SHUTDOWN -> "☢️"

                        else -> "🙂"
                    }
                    if (_binding != null) {
                        binding.showThermalsText.text = thermalStatus
                    }
                    thermalStatsUpdateHandler.postDelayed(thermalStatsUpdater!!, 1000)
                }
            }
            thermalStatsUpdateHandler.post(thermalStatsUpdater!!)
        } else {
            if (thermalStatsUpdater != null) {
                thermalStatsUpdateHandler.removeCallbacks(thermalStatsUpdater!!)
            }
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun updateOrientation() {
        emulationActivity?.let {
            val orientationSetting =
                EmulationOrientation.from(IntSetting.RENDERER_SCREEN_LAYOUT.getInt())
            it.requestedOrientation = when (orientationSetting) {
                EmulationOrientation.Unspecified -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                EmulationOrientation.SensorLandscape ->
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

                EmulationOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                EmulationOrientation.ReverseLandscape ->
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE

                EmulationOrientation.SensorPortrait ->
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

                EmulationOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                EmulationOrientation.ReversePortrait ->
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            }
        }
    }

    private fun updateScreenLayout() {
        val verticalAlignment =
            EmulationVerticalAlignment.from(IntSetting.VERTICAL_ALIGNMENT.getInt())
        val aspectRatio = when (IntSetting.RENDERER_ASPECT_RATIO.getInt()) {
            0 -> Rational(16, 9)
            1 -> Rational(4, 3)
            2 -> Rational(21, 9)
            3 -> Rational(16, 10)
            else -> null // Best fit
        }
        when (verticalAlignment) {
            EmulationVerticalAlignment.Top -> {
                binding.surfaceEmulation.setAspectRatio(aspectRatio)
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                binding.surfaceEmulation.layoutParams = params
            }

            EmulationVerticalAlignment.Center -> {
                binding.surfaceEmulation.setAspectRatio(null)
                binding.surfaceEmulation.updateLayoutParams {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }

            EmulationVerticalAlignment.Bottom -> {
                binding.surfaceEmulation.setAspectRatio(aspectRatio)
                val params =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                binding.surfaceEmulation.layoutParams = params
            }
        }
        emulationState.updateSurface()
        emulationActivity?.buildPictureInPictureParams()
        updateOrientation()
    }

    private fun updateFoldableLayout(
        emulationActivity: EmulationActivity,
        newLayoutInfo: WindowLayoutInfo
    ) {
        val isFolding =
            (newLayoutInfo.displayFeatures.find { it is FoldingFeature } as? FoldingFeature)?.let {
                if (it.isSeparating) {
                    emulationActivity.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    if (it.orientation == FoldingFeature.Orientation.HORIZONTAL) {
                        // Restrict emulation and overlays to the top of the screen
                        binding.emulationContainer.layoutParams.height = it.bounds.top
                        // Restrict input and menu drawer to the bottom of the screen
                        binding.inputContainer.layoutParams.height = it.bounds.bottom
                        binding.inGameMenu.layoutParams.height = it.bounds.bottom

                        isInFoldableLayout = true
                        binding.surfaceInputOverlay.layout = OverlayLayout.Foldable
                    }
                }
                it.isSeparating
            } ?: false
        if (!isFolding) {
            binding.emulationContainer.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.inputContainer.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.inGameMenu.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            isInFoldableLayout = false
            updateOrientation()
            onConfigurationChanged(resources.configuration)
        }
        binding.emulationContainer.requestLayout()
        binding.inputContainer.requestLayout()
        binding.inGameMenu.requestLayout()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // We purposely don't do anything here.
        // All work is done in surfaceChanged, which we are guaranteed to get even for surface creation.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.debug("[EmulationFragment] Surface changed. Resolution: " + width + "x" + height)
        emulationState.newSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        emulationState.clearSurface()
    }

    private fun showOverlayOptions() {
        val anchor = binding.inGameMenu.findViewById<View>(R.id.menu_overlay_controls)
        val popup = IronLemonPopupMenu(requireContext(), anchor)

        popup.menuInflater.inflate(R.menu.menu_overlay_options, popup.menu)

        popup.menu.apply {
            findItem(R.id.menu_toggle_fps).isChecked =
                BooleanSetting.SHOW_PERFORMANCE_OVERLAY.getBoolean()
            findItem(R.id.thermal_indicator).isChecked =
                BooleanSetting.SHOW_THERMAL_OVERLAY.getBoolean()
            findItem(R.id.menu_rel_stick_center).isChecked =
                BooleanSetting.JOYSTICK_REL_CENTER.getBoolean()
            findItem(R.id.menu_dpad_slide).isChecked = BooleanSetting.DPAD_SLIDE.getBoolean()
            findItem(R.id.menu_show_overlay).isChecked =
                BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()
            findItem(R.id.menu_haptics).isChecked = BooleanSetting.HAPTIC_FEEDBACK.getBoolean()
            findItem(R.id.menu_touchscreen).isChecked = BooleanSetting.TOUCHSCREEN.getBoolean()
        }

        popup.setOnDismissListener { NativeConfig.saveGlobalConfig() }
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_toggle_fps -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.SHOW_PERFORMANCE_OVERLAY.setBoolean(it.isChecked)
                    updateShowFpsOverlay()
                    true
                }

                R.id.thermal_indicator -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.SHOW_THERMAL_OVERLAY.setBoolean(it.isChecked)
                    updateThermalOverlay()
                    true
                }

                R.id.menu_edit_overlay -> {
                    binding.drawerLayout.close()
                    binding.surfaceInputOverlay.requestFocus()
                    startConfiguringControls()
                    true
                }

                R.id.menu_adjust_overlay -> {
                    adjustOverlay()
                    true
                }

                R.id.menu_toggle_controls -> {
                    val overlayControlData = NativeConfig.getOverlayControlData()
                    val optionsArray = BooleanArray(overlayControlData.size)
                    overlayControlData.forEachIndexed { i, _ ->
                        optionsArray[i] = overlayControlData.firstOrNull { data ->
                            OverlayControl.entries[i].id == data.id
                        }?.enabled == true
                    }

                    showToggleControls(overlayControlData, optionsArray)
                    true
                }

                R.id.menu_show_overlay -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.SHOW_INPUT_OVERLAY.setBoolean(it.isChecked)
                    binding.surfaceInputOverlay.refreshControls()
                    true
                }

                R.id.menu_rel_stick_center -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.JOYSTICK_REL_CENTER.setBoolean(it.isChecked)
                    true
                }

                R.id.menu_dpad_slide -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.DPAD_SLIDE.setBoolean(it.isChecked)
                    true
                }

                R.id.menu_haptics -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.HAPTIC_FEEDBACK.setBoolean(it.isChecked)
                    true
                }

                R.id.menu_touchscreen -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.TOUCHSCREEN.setBoolean(it.isChecked)
                    true
                }

                R.id.menu_reset_overlay -> {
                    binding.drawerLayout.close()
                    resetInputOverlay()
                    true
                }

                else -> true
            }
        }

        popup.show()
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun startConfiguringControls() {
        // Lock the current orientation to prevent editing inconsistencies
        if (IntSetting.RENDERER_SCREEN_LAYOUT.getInt() == EmulationOrientation.Unspecified.int) {
            emulationActivity?.let {
                it.requestedOrientation =
                    if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                    }
            }
        }
        binding.doneControlConfig.setVisible(true)
        binding.surfaceInputOverlay.setIsInEditMode(true)
    }

    private fun stopConfiguringControls() {
        binding.doneControlConfig.setVisible(false)
        binding.surfaceInputOverlay.setIsInEditMode(false)
        // Unlock the orientation if it was locked for editing
        if (IntSetting.RENDERER_SCREEN_LAYOUT.getInt() == EmulationOrientation.Unspecified.int) {
            emulationActivity?.let {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        NativeConfig.saveGlobalConfig()
    }

    @SuppressLint("SetTextI18n")
    /** Which on-screen controls are shown: a switch each, with one to flip them all. */
    private fun showToggleControls(
        overlayControlData: Array<OverlayControlData>,
        initial: BooleanArray
    ) {
        val names = resources.getStringArray(R.array.gamepadButtons)
        showIronLemonDialog(requireContext()) { dismiss ->
            val checked = remember { mutableStateListOf(*initial.toTypedArray()) }
            IronLemonDialogCard(
                title = stringResource(R.string.emulation_toggle_controls),
                buttons = {
                    IronLemonButton(
                        text = stringResource(R.string.emulation_toggle_all),
                        height = 44.dp,
                        onClick = {
                            val all = !checked[0]
                            checked.indices.forEach { checked[it] = all }
                        }
                    )
                    IronLemonButton(
                        text = stringResource(android.R.string.cancel),
                        height = 44.dp,
                        onClick = dismiss
                    )
                    IronLemonButton(
                        text = stringResource(android.R.string.ok),
                        primary = true,
                        height = 44.dp,
                        onClick = {
                            checked.forEachIndexed { index, enabled ->
                                overlayControlData.firstOrNull { data ->
                                    OverlayControl.entries[index].id == data.id
                                }?.enabled = enabled
                            }
                            NativeConfig.setOverlayControlData(overlayControlData)
                            NativeConfig.saveGlobalConfig()
                            binding.surfaceInputOverlay.refreshControls()
                            dismiss()
                        }
                    )
                }
            ) {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    checked.indices.forEach { index ->
                        IronLemonSwitchRow(names.getOrElse(index) { "" }, checked[index]) {
                            checked[index] = it
                        }
                    }
                }
            }
        }
    }

    /** The on-screen controller's size and opacity, applied as the sliders move. */
    private fun adjustOverlay() {
        val percent = NumberFormat.getPercentInstance()
        showIronLemonDialog(requireContext(), onDismiss = { NativeConfig.saveGlobalConfig() }) { dismiss ->
            var scale by remember { mutableIntStateOf(IntSetting.OVERLAY_SCALE.getInt()) }
            var opacity by remember { mutableIntStateOf(IntSetting.OVERLAY_OPACITY.getInt()) }
            IronLemonDialogCard(
                title = stringResource(R.string.emulation_control_adjust),
                buttons = {
                    IronLemonButton(
                        text = stringResource(R.string.slider_default),
                        height = 44.dp,
                        onClick = {
                            scale = 50
                            opacity = 100
                            setControlScale(50)
                            setControlOpacity(100)
                        }
                    )
                    IronLemonButton(
                        text = stringResource(android.R.string.ok),
                        primary = true,
                        height = 44.dp,
                        onClick = dismiss
                    )
                }
            ) {
                SliderLine(
                    stringResource(R.string.emulation_control_scale),
                    percent.format(scale / 100.0),
                    scale,
                    150
                ) {
                    scale = it
                    setControlScale(it)
                }
                Spacer(Modifier.height(16.dp))
                SliderLine(
                    stringResource(R.string.emulation_control_opacity),
                    percent.format(opacity / 100.0),
                    opacity,
                    100
                ) {
                    opacity = it
                    setControlOpacity(it)
                }
            }
        }
    }

    @Composable
    private fun SliderLine(label: String, value: String, current: Int, max: Int, onChange: (Int) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, style = IronLemonType.Button, modifier = Modifier.weight(1f))
            BasicText(value, style = IronLemonType.Button.copy(color = IronLemonColors.Lemon))
        }
        Spacer(Modifier.height(6.dp))
        IronLemonSlider(value = current, steps = max, onChange = onChange)
    }

    private fun setControlScale(scale: Int) {
        IntSetting.OVERLAY_SCALE.setInt(scale)
        binding.surfaceInputOverlay.refreshControls()
    }

    private fun setControlOpacity(opacity: Int) {
        IntSetting.OVERLAY_OPACITY.setInt(opacity)
        binding.surfaceInputOverlay.refreshControls()
    }

    private fun setInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.inGameMenu
        ) { v: View, windowInsets: WindowInsetsCompat ->
            val cutInsets: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            var left = 0
            var right = 0
            if (ViewCompat.getLayoutDirection(v) == ViewCompat.LAYOUT_DIRECTION_LTR) {
                left = cutInsets.left
            } else {
                right = cutInsets.right
            }

            v.updatePadding(left = left, top = cutInsets.top, right = right)
            windowInsets
        }
    }

    private class EmulationState(
        private val gamePath: String,
        private val emulationCanStart: () -> Boolean
    ) {
        private var state: State
        private var surface: Surface? = null
        lateinit var emulationThread: Thread

        init {
            // Starting state is stopped.
            state = State.STOPPED
        }

        @get:Synchronized
        val isStopped: Boolean
            get() = state == State.STOPPED

        // Getters for the current state
        @get:Synchronized
        val isPaused: Boolean
            get() = state == State.PAUSED

        @get:Synchronized
        val isRunning: Boolean
            get() = state == State.RUNNING

        @Synchronized
        fun stop() {
            if (state != State.STOPPED) {
                Log.debug("[EmulationFragment] Stopping emulation.")
                NativeLibrary.stopEmulation()
                state = State.STOPPED
            } else {
                Log.warning("[EmulationFragment] Stop called while already stopped.")
            }
        }

        // State changing methods
        @Synchronized
        fun pause() {
            if (state != State.PAUSED) {
                Log.debug("[EmulationFragment] Pausing emulation.")

                NativeLibrary.pauseEmulation()

                state = State.PAUSED
            } else {
                Log.warning("[EmulationFragment] Pause called while already paused.")
            }
        }

        @Synchronized
        fun run(isActivityRecreated: Boolean, programIndex: Int = 0) {
            if (isActivityRecreated) {
                if (NativeLibrary.isRunning()) {
                    state = State.PAUSED
                }
            } else {
                Log.debug("[EmulationFragment] activity resumed or fresh start")
            }

            // If the surface is set, run now. Otherwise, wait for it to get set.
            if (surface != null) {
                runWithValidSurface(programIndex)
            }
        }

        @Synchronized
        fun changeProgram(programIndex: Int) {
            emulationThread.join()
            emulationThread = Thread({
                Log.debug("[EmulationFragment] Starting emulation thread.")
                NextendoAccount.prepareForBoot()
                NativeLibrary.run(gamePath, programIndex, false)
            }, "NativeEmulation")
            emulationThread.start()
        }

        // Surface callbacks
        @Synchronized
        fun newSurface(surface: Surface?) {
            this.surface = surface
            if (this.surface != null) {
                runWithValidSurface()
            }
        }

        @Synchronized
        fun updateSurface() {
            if (surface != null) {
                NativeLibrary.surfaceChanged(surface)
            }
        }

        @Synchronized
        fun clearSurface() {
            if (surface == null) {
                Log.warning("[EmulationFragment] clearSurface called, but surface already null.")
            } else {
                surface = null
                Log.debug("[EmulationFragment] Surface destroyed.")
                when (state) {
                    State.RUNNING -> {
                        state = State.PAUSED
                    }

                    State.PAUSED -> Log.warning(
                        "[EmulationFragment] Surface cleared while emulation paused."
                    )

                    else -> Log.warning(
                        "[EmulationFragment] Surface cleared while emulation stopped."
                    )
                }
            }
        }

        private fun runWithValidSurface(programIndex: Int = 0) {
            NativeLibrary.surfaceChanged(surface)
            if (!emulationCanStart.invoke()) {
                return
            }

            when (state) {
                State.STOPPED -> {
                    emulationThread = Thread({
                        Log.debug("[EmulationFragment] Starting emulation thread.")
                        NextendoAccount.prepareForBoot()
                        NativeLibrary.run(gamePath, programIndex, true)
                    }, "NativeEmulation")
                    emulationThread.start()
                }

                State.PAUSED -> {
                    Log.debug("[EmulationFragment] Resuming emulation.")
                    NativeLibrary.unpauseEmulation()
                }

                else -> Log.debug("[EmulationFragment] Bug, run called while already running.")
            }
            state = State.RUNNING
        }

        private enum class State {
            STOPPED, RUNNING, PAUSED
        }
    }

    companion object {
        private val perfStatsUpdateHandler = Handler(Looper.myLooper()!!)
        private val thermalStatsUpdateHandler = Handler(Looper.myLooper()!!)

        // Just under a long toast's 3.5 s, so the next one follows without a gap.
        private const val QUICK_STATE_WORKING_SHOWN_MS = 3400L
        private const val QUICK_STATE_SUCCESS_SHOWN_MS = 1000L

        // The loading screen waits for the game's first frame, checked this often, at most this long.
        private const val FIRST_FRAME_POLL_MS = 200L
        private const val FIRST_FRAME_TIMEOUT_MS = 60_000L
        private const val FIRST_FRAMES_SHOWN = 10L

        private const val ADAPTIVE_PERFORMANCE_NOTIFICATION_ID = 4242

        // The width of a quicksave slot's picture, in pixels.
        private const val QUICK_SAVE_THUMBNAIL_WIDTH = 320
    }
}
