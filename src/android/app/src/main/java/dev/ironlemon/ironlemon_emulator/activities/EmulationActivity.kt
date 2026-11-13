// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.activities

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.YuzuApplication
import dev.ironlemon.ironlemon_emulator.databinding.ActivityEmulationBinding
import dev.ironlemon.ironlemon_emulator.fragments.EmulationFragment
import dev.ironlemon.ironlemon_emulator.features.input.GamepadHotkeyTracker
import dev.ironlemon.ironlemon_emulator.features.input.GamepadHotkeys
import dev.ironlemon.ironlemon_emulator.features.input.NativeInput
import dev.ironlemon.ironlemon_emulator.features.settings.model.BooleanSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.IntSetting
import dev.ironlemon.ironlemon_emulator.features.settings.model.Settings
import dev.ironlemon.ironlemon_emulator.model.EmulationViewModel
import dev.ironlemon.ironlemon_emulator.model.Game
import dev.ironlemon.ironlemon_emulator.utils.InputHandler
import dev.ironlemon.ironlemon_emulator.utils.Log
import dev.ironlemon.ironlemon_emulator.utils.MemoryUtil
import dev.ironlemon.ironlemon_emulator.utils.NativeConfig
import dev.ironlemon.ironlemon_emulator.utils.NfcReader
import dev.ironlemon.ironlemon_emulator.utils.ParamPackage
import dev.ironlemon.ironlemon_emulator.utils.ThemeHelper
import java.text.NumberFormat
import kotlin.math.roundToInt

class EmulationActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var binding: ActivityEmulationBinding

    var isActivityRecreated = false
    private lateinit var nfcReader: NfcReader

    private val gyro = FloatArray(3)
    private val accel = FloatArray(3)
    private var motionTimestamp: Long = 0
    private var flipMotionOrientation: Boolean = false

    private val actionPause = "ACTION_EMULATOR_PAUSE"
    private val actionPlay = "ACTION_EMULATOR_PLAY"
    private val actionMute = "ACTION_EMULATOR_MUTE"
    private val actionUnmute = "ACTION_EMULATOR_UNMUTE"

    private val emulationViewModel: EmulationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.gameLaunched = true
        ThemeHelper.setTheme(this)

        super.onCreate(savedInstanceState)

        InputHandler.updateControllerData()
        val players = NativeConfig.getInputSettings(true)
        var hasConfiguredControllers = false
        players.forEach {
            if (it.hasMapping()) {
                hasConfiguredControllers = true
            }
        }
        if (!hasConfiguredControllers && InputHandler.androidControllers.isNotEmpty()) {
            var params: ParamPackage? = null
            for (controller in InputHandler.registeredControllers) {
                if (controller.get("port", -1) == 0) {
                    params = controller
                    break
                }
            }

            if (params != null) {
                NativeInput.updateMappingsWithDefault(
                    0,
                    params,
                    params.get("display", getString(R.string.unknown))
                )
                NativeConfig.saveGlobalConfig()
            }
        }

        binding = ActivityEmulationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        navHostFragment.navController.setGraph(R.navigation.emulation_navigation, intent.extras)

        isActivityRecreated = savedInstanceState != null

        // Set these options now so that the SurfaceView the game renders into is the right size.
        enableFullscreenImmersive()

        window.decorView.setBackgroundColor(getColor(android.R.color.black))

        nfcReader = NfcReader(this)
        nfcReader.initialize()

        val preferences = PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)
        if (!preferences.getBoolean(Settings.PREF_MEMORY_WARNING_SHOWN, false)) {
            if (MemoryUtil.isLessThan(MemoryUtil.REQUIRED_MEMORY, MemoryUtil.totalMemory)) {
                Toast.makeText(
                    this,
                    getString(
                        R.string.device_memory_inadequate,
                        MemoryUtil.getDeviceRAM(),
                        getString(
                            R.string.memory_formatted,
                            NumberFormat.getInstance().format(MemoryUtil.REQUIRED_MEMORY),
                            getString(R.string.memory_gigabyte)
                        )
                    ),
                    Toast.LENGTH_LONG
                ).show()
                preferences.edit()
                    .putBoolean(Settings.PREF_MEMORY_WARNING_SHOWN, true)
                    .apply()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                // Special case, we do not support multiline input, dismiss the keyboard.
                val overlayView: View =
                    this.findViewById(R.id.surface_input_overlay)
                val im =
                    overlayView.context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                im.hideSoftInputFromWindow(overlayView.windowToken, 0)
            } else {
                val textChar = event.unicodeChar
                if (textChar == 0) {
                    // No text, button input.
                    NativeLibrary.submitInlineKeyboardInput(keyCode)
                } else {
                    // Text submitted.
                    NativeLibrary.submitInlineKeyboardText(textChar.toChar().toString())
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        nfcReader.startScanning()
        startMotionSensorListener()
        InputHandler.updateControllerData()
        hotkeys.reload()

        buildPictureInPictureParams()
    }

    override fun onPause() {
        super.onPause()
        nfcReader.stopScanning()
        stopMotionSensorListener()
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (BooleanSetting.PICTURE_IN_PICTURE.getBoolean() && !isInPictureInPictureMode) {
                val pictureInPictureParamsBuilder = PictureInPictureParams.Builder()
                    .getPictureInPictureActionsBuilder().getPictureInPictureAspectBuilder()
                enterPictureInPictureMode(pictureInPictureParamsBuilder.build())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nfcReader.onNewIntent(intent)
        InputHandler.updateControllerData()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
        ) {
            return super.dispatchKeyEvent(event)
        }

        // These run before the drawer check so their button state stays right while the menu is
        // open.
        // Only controller events get here (the rest returned above).
        run {
            val menuHotkey = handleMenuHotkeys(event)
            // While the menu is open, buttons can be let go without the release coming here (the
            // controller navigation hook remaps them): hotkeys start again from nothing held.
            val hotkey = if (emulationViewModel.drawerOpen.value) {
                hotkeys.reset()
                false
            } else {
                hotkeys.onKeyEvent(
                    event,
                    canFire = !menuHotkey && NativeLibrary.isRunning(),
                    run = ::runHotkey
                )
            }
            if (menuHotkey || hotkey) {
                return true
            }
        }

        if (emulationViewModel.drawerOpen.value) {
            return super.dispatchKeyEvent(event)
        }

        return InputHandler.dispatchKeyEvent(event)
    }

    private val hotkeys = GamepadHotkeyTracker { InputHandler.dispatchKeyEvent(it) }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Buttons let go while another window has focus never send their release here.
        if (!hasFocus) {
            hotkeys.reset()
        }
    }

    private var exitHotkeyPressedAt = 0L

    private fun findEmulationFragment(): EmulationFragment? {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
        return navHostFragment?.childFragmentManager?.fragments?.firstOrNull() as? EmulationFragment
    }

    private fun runHotkey(action: GamepadHotkeys.Action) {
        val emulationFragment = findEmulationFragment() ?: return
        when (action) {
            GamepadHotkeys.Action.QUICK_SAVE -> emulationFragment.quickSaveState()
            GamepadHotkeys.Action.QUICK_LOAD -> emulationFragment.quickLoadState()
            GamepadHotkeys.Action.TOGGLE_STATS_OVERLAY -> emulationFragment.toggleStatsOverlay()
            GamepadHotkeys.Action.TOGGLE_SOC_OVERLAY -> emulationFragment.toggleSocOverlay()
            GamepadHotkeys.Action.PAUSE_EMULATION -> emulationFragment.togglePauseEmulation()
            GamepadHotkeys.Action.EXIT_EMULATION -> {
                // Exiting loses what was not saved: unless turned off, it takes a second press, as
                // RetroArch's quit.
                val now = SystemClock.uptimeMillis()
                if (!BooleanSetting.HOTKEY_CONFIRM_EXIT.getBoolean() ||
                    now - exitHotkeyPressedAt < EXIT_HOTKEY_CONFIRM_MS
                ) {
                    exitHotkeyPressedAt = 0L
                    emulationFragment.exitEmulation()
                } else {
                    exitHotkeyPressedAt = now
                    Toast.makeText(this, R.string.hotkey_exit_press_again, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    /** Tells the game that [keyCode], pressed on the controller of [event], was let go. */
    private fun releaseInGame(event: KeyEvent, keyCode: Int) {
        InputHandler.dispatchKeyEvent(
            KeyEvent(
                event.downTime,
                event.eventTime,
                KeyEvent.ACTION_UP,
                keyCode,
                0,
                event.metaState,
                event.deviceId,
                0,
                event.flags,
                event.source
            )
        )
    }

    private var hotkeyStickDown = false
    private var hotkeyStartDown = false
    private var hotkeyChordActive = false

    /**
     * Gamepad shortcuts for the in-game menu, for controllers without a Back button (Xbox and the
     * like): R3 (right stick click) + Start together, or the Guide button, toggle it, and B
     * closes it. The chord releases the first button in the game so it is not left held down.
     */
    private fun handleMenuHotkeys(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        val drawerOpen = emulationViewModel.drawerOpen.value

        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                if (!down && NativeLibrary.isRunning()) {
                    emulationViewModel.setDrawerOpen(!drawerOpen)
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (!drawerOpen) {
                    return false
                }
                if (!down) {
                    emulationViewModel.setDrawerOpen(false)
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> hotkeyStickDown = down
            KeyEvent.KEYCODE_BUTTON_START -> hotkeyStartDown = down
            else -> return false
        }

        if (hotkeyChordActive) {
            // The chord already fired: swallow what is left of it.
            if (!hotkeyStickDown && !hotkeyStartDown) {
                hotkeyChordActive = false
            }
            return true
        }

        if (down && hotkeyStickDown && hotkeyStartDown && NativeLibrary.isRunning()) {
            hotkeyChordActive = true
            if (!drawerOpen) {
                val first = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
                    KeyEvent.KEYCODE_BUTTON_THUMBR
                } else {
                    KeyEvent.KEYCODE_BUTTON_START
                }
                releaseInGame(event, first)
            }
            emulationViewModel.setDrawerOpen(!drawerOpen)
            return true
        }
        return false
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
        ) {
            return super.dispatchGenericMotionEvent(event)
        }

        if (emulationViewModel.drawerOpen.value) {
            return super.dispatchGenericMotionEvent(event)
        }

        // Don't attempt to do anything if we are disconnecting a device.
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            return true
        }

        return InputHandler.dispatchGenericMotionEvent(event)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val rotation = this.display?.rotation
        if (rotation == Surface.ROTATION_90) {
            flipMotionOrientation = true
        }
        if (rotation == Surface.ROTATION_270) {
            flipMotionOrientation = false
        }

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            if (flipMotionOrientation) {
                accel[0] = event.values[1] / SensorManager.GRAVITY_EARTH
                accel[1] = -event.values[0] / SensorManager.GRAVITY_EARTH
            } else {
                accel[0] = -event.values[1] / SensorManager.GRAVITY_EARTH
                accel[1] = event.values[0] / SensorManager.GRAVITY_EARTH
            }
            accel[2] = -event.values[2] / SensorManager.GRAVITY_EARTH
        }
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            // Investigate why sensor value is off by 6x
            if (flipMotionOrientation) {
                gyro[0] = -event.values[1] / 6.0f
                gyro[1] = event.values[0] / 6.0f
            } else {
                gyro[0] = event.values[1] / 6.0f
                gyro[1] = -event.values[0] / 6.0f
            }
            gyro[2] = event.values[2] / 6.0f
        }

        // Only update state on accelerometer data
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) {
            return
        }
        val deltaTimestamp = (event.timestamp - motionTimestamp) / 1000
        motionTimestamp = event.timestamp
        NativeInput.onDeviceMotionEvent(
            NativeInput.Player1Device,
            deltaTimestamp,
            gyro[0],
            gyro[1],
            gyro[2],
            accel[0],
            accel[1],
            accel[2]
        )
        NativeInput.onDeviceMotionEvent(
            NativeInput.ConsoleDevice,
            deltaTimestamp,
            gyro[0],
            gyro[1],
            gyro[2],
            accel[0],
            accel[1],
            accel[2]
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, i: Int) {}

    private fun enableFullscreenImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun PictureInPictureParams.Builder.getPictureInPictureAspectBuilder():
        PictureInPictureParams.Builder {
        val aspectRatio = when (IntSetting.RENDERER_ASPECT_RATIO.getInt()) {
            0 -> Rational(16, 9)
            1 -> Rational(4, 3)
            2 -> Rational(21, 9)
            3 -> Rational(16, 10)
            else -> null // Best fit
        }
        return this.apply { aspectRatio?.let { setAspectRatio(it) } }
    }

    private fun PictureInPictureParams.Builder.getPictureInPictureActionsBuilder():
        PictureInPictureParams.Builder {
        val pictureInPictureActions: MutableList<RemoteAction> = mutableListOf()
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        if (NativeLibrary.isPaused()) {
            val playIcon = Icon.createWithResource(this@EmulationActivity, R.drawable.ic_pip_play)
            val playPendingIntent = PendingIntent.getBroadcast(
                this@EmulationActivity,
                R.drawable.ic_pip_play,
                Intent(actionPlay),
                pendingFlags
            )
            val playRemoteAction = RemoteAction(
                playIcon,
                getString(R.string.play),
                getString(R.string.play),
                playPendingIntent
            )
            pictureInPictureActions.add(playRemoteAction)
        } else {
            val pauseIcon = Icon.createWithResource(this@EmulationActivity, R.drawable.ic_pip_pause)
            val pausePendingIntent = PendingIntent.getBroadcast(
                this@EmulationActivity,
                R.drawable.ic_pip_pause,
                Intent(actionPause),
                pendingFlags
            )
            val pauseRemoteAction = RemoteAction(
                pauseIcon,
                getString(R.string.pause),
                getString(R.string.pause),
                pausePendingIntent
            )
            pictureInPictureActions.add(pauseRemoteAction)
        }

        if (BooleanSetting.AUDIO_MUTED.getBoolean()) {
            val unmuteIcon = Icon.createWithResource(
                this@EmulationActivity,
                R.drawable.ic_pip_unmute
            )
            val unmutePendingIntent = PendingIntent.getBroadcast(
                this@EmulationActivity,
                R.drawable.ic_pip_unmute,
                Intent(actionUnmute),
                pendingFlags
            )
            val unmuteRemoteAction = RemoteAction(
                unmuteIcon,
                getString(R.string.unmute),
                getString(R.string.unmute),
                unmutePendingIntent
            )
            pictureInPictureActions.add(unmuteRemoteAction)
        } else {
            val muteIcon = Icon.createWithResource(this@EmulationActivity, R.drawable.ic_pip_mute)
            val mutePendingIntent = PendingIntent.getBroadcast(
                this@EmulationActivity,
                R.drawable.ic_pip_mute,
                Intent(actionMute),
                pendingFlags
            )
            val muteRemoteAction = RemoteAction(
                muteIcon,
                getString(R.string.mute),
                getString(R.string.mute),
                mutePendingIntent
            )
            pictureInPictureActions.add(muteRemoteAction)
        }

        return this.apply { setActions(pictureInPictureActions) }
    }

    fun buildPictureInPictureParams() {
        val pictureInPictureParamsBuilder = PictureInPictureParams.Builder()
            .getPictureInPictureActionsBuilder().getPictureInPictureAspectBuilder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isEmulationActive = emulationViewModel.emulationStarted.value &&
                !emulationViewModel.isEmulationStopping.value
            pictureInPictureParamsBuilder.setAutoEnterEnabled(
                BooleanSetting.PICTURE_IN_PICTURE.getBoolean() && isEmulationActive
            )
        }
        setPictureInPictureParams(pictureInPictureParamsBuilder.build())
    }

    private var pictureInPictureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == actionPlay) {
                if (NativeLibrary.isPaused()) NativeLibrary.unpauseEmulation()
            } else if (intent.action == actionPause) {
                if (!NativeLibrary.isPaused()) NativeLibrary.pauseEmulation()
            }
            if (intent.action == actionUnmute) {
                if (BooleanSetting.AUDIO_MUTED.getBoolean()) {
                    BooleanSetting.AUDIO_MUTED.setBoolean(false)
                }
            } else if (intent.action == actionMute) {
                if (!BooleanSetting.AUDIO_MUTED.getBoolean()) {
                    BooleanSetting.AUDIO_MUTED.setBoolean(true)
                }
            }
            buildPictureInPictureParams()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            IntentFilter().apply {
                addAction(actionPause)
                addAction(actionPlay)
                addAction(actionMute)
                addAction(actionUnmute)
            }.also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(pictureInPictureReceiver, it, RECEIVER_EXPORTED)
                } else {
                    registerReceiver(pictureInPictureReceiver, it)
                }
            }
        } else {
            try {
                unregisterReceiver(pictureInPictureReceiver)
            } catch (ignored: Exception) {
            }
            // Always resume audio, since there is no UI button
            if (BooleanSetting.AUDIO_MUTED.getBoolean()) {
                BooleanSetting.AUDIO_MUTED.setBoolean(false)
            }
        }
    }

    fun onEmulationStarted() {
        emulationViewModel.setEmulationStarted(true)
    }

    fun onEmulationStopped(status: Int) {
        if (status == 0 && emulationViewModel.programChanged.value == -1) {
            finish()
        }
        emulationViewModel.setEmulationStopped(true)
    }

    fun onProgramChanged(programIndex: Int) {
        emulationViewModel.setProgramChanged(programIndex)
    }

    private fun startMotionSensorListener() {
        val sensorManager = this.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopMotionSensorListener() {
        val sensorManager = this.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        sensorManager.unregisterListener(this, gyroSensor)
        sensorManager.unregisterListener(this, accelSensor)
    }

    companion object {
        private const val EXIT_HOTKEY_CONFIRM_MS = 2000L
        const val EXTRA_SELECTED_GAME = "SelectedGame"

        fun launch(activity: AppCompatActivity, game: Game) {
            val launcher = Intent(activity, EmulationActivity::class.java)
            launcher.putExtra(EXTRA_SELECTED_GAME, game)
            activity.startActivity(launcher)
        }

        private fun areCoordinatesOutside(view: View?, x: Float, y: Float): Boolean {
            if (view == null) {
                return true
            }
            val viewBounds = Rect()
            view.getGlobalVisibleRect(viewBounds)
            return !viewBounds.contains(x.roundToInt(), y.roundToInt())
        }
    }
}
