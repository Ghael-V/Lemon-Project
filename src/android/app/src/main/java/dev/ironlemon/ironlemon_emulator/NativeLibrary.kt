// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonDialogText
import dev.ironlemon.ironlemon_emulator.ui.modern.showIronLemonDialog
import android.content.DialogInterface
import android.net.Uri
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Surface
import android.view.View
import android.widget.TextView
import androidx.annotation.Keep
import java.lang.ref.WeakReference
import dev.ironlemon.ironlemon_emulator.activities.EmulationActivity
import dev.ironlemon.ironlemon_emulator.fragments.CoreErrorDialogFragment
import dev.ironlemon.ironlemon_emulator.utils.DocumentsTree
import dev.ironlemon.ironlemon_emulator.utils.FileUtil
import dev.ironlemon.ironlemon_emulator.utils.Log
import dev.ironlemon.ironlemon_emulator.model.InstallResult
import dev.ironlemon.ironlemon_emulator.model.Patch
import dev.ironlemon.ironlemon_emulator.model.GameVerificationResult

/**
 * Class which contains methods that interact
 * with the native side of the Yuzu code.
 */
object NativeLibrary {
    @JvmField
    var sEmulationActivity = WeakReference<EmulationActivity?>(null)

    init {
        try {
            System.loadLibrary("yuzu-android")
        } catch (ex: UnsatisfiedLinkError) {
            error("[NativeLibrary] $ex")
        }
    }

    @Keep
    @JvmStatic
    fun openContentUri(path: String?, openmode: String?): Int {
        return if (DocumentsTree.isNativePath(path!!)) {
            YuzuApplication.documentsTree!!.openContentUri(path, openmode)
        } else {
            FileUtil.openContentUri(path, openmode)
        }
    }

    @Keep
    @JvmStatic
    fun getSize(path: String?): Long {
        return if (DocumentsTree.isNativePath(path!!)) {
            YuzuApplication.documentsTree!!.getFileSize(path)
        } else {
            FileUtil.getFileSize(path)
        }
    }

    @Keep
    @JvmStatic
    fun exists(path: String?): Boolean {
        return if (DocumentsTree.isNativePath(path!!)) {
            YuzuApplication.documentsTree!!.exists(path)
        } else {
            FileUtil.exists(path, suppressLog = true)
        }
    }

    @Keep
    @JvmStatic
    fun isDirectory(path: String?): Boolean {
        return if (DocumentsTree.isNativePath(path!!)) {
            YuzuApplication.documentsTree!!.isDirectory(path)
        } else {
            FileUtil.isDirectory(path)
        }
    }

    @Keep
    @JvmStatic
    fun getParentDirectory(path: String): String =
        if (DocumentsTree.isNativePath(path)) {
            YuzuApplication.documentsTree!!.getParentDirectory(path)
        } else {
            path
        }

    @Keep
    @JvmStatic
    fun getFilename(path: String): String =
        if (DocumentsTree.isNativePath(path)) {
            YuzuApplication.documentsTree!!.getFilename(path)
        } else {
            FileUtil.getFilename(Uri.parse(path))
        }

    external fun setAppDirectory(directory: String)

    /**
     * Installs a nsp or xci file to nand
     * @param filename String representation of file uri
     * @return int representation of [InstallResult]
     */
    external fun installFileToNand(
        filename: String,
        callback: (max: Long, progress: Long) -> Boolean
    ): Int

    external fun doesUpdateMatchProgram(programId: String, updatePath: String): Boolean

    external fun initializeGpuDriver(
        hookLibDir: String?,
        customDriverDir: String?,
        customDriverName: String?,
        fileRedirectDir: String?
    )

    external fun reloadKeys(): Boolean

    external fun initializeSystem(reload: Boolean)

    /**
     * Begins emulation.
     */
    external fun run(path: String?, programIndex: Int, frontendInitiated: Boolean)

    // Surface Handling
    external fun surfaceChanged(surf: Surface?)

    external fun surfaceDestroyed()

    /**
     * Unpauses emulation from a paused state.
     */
    external fun unpauseEmulation()

    /**
     * Pauses emulation.
     */
    external fun pauseEmulation()

    /**
     * Captures CPU register state and process memory to a single quicksave slot.
     * Same-session only - does not survive closing the game. Returns one of the QUICK_SAVE_*
     * results; it can wait up to about three seconds for a safe moment.
     */
    external fun quickSaveState(): Int

    const val QUICK_SAVE_FAILED = 0 // the save could not be written
    const val QUICK_SAVE_SAVED = 1 // saved
    const val QUICK_SAVE_NO_SAFE_MOMENT = 2 // a lock handoff never ended; nothing was saved

    /**
     * Restores CPU register state and process memory from the quicksave slot. Returns one of the
     * QUICK_LOAD_* results.
     */
    external fun quickLoadState(): Int

    const val QUICK_LOAD_FAILED = 0 // nothing was restored
    const val QUICK_LOAD_LOADED = 1 // restored
    const val QUICK_LOAD_INCOMPATIBLE = 2 // refused: a change since the save cannot be undone
    const val QUICK_LOAD_NO_SAFE_MOMENT = 3 // a lock handoff never ended; nothing was changed
    const val QUICK_LOAD_GAME_LOADING = 4 // threads of the save are not back yet; nothing was changed
    const val QUICK_LOAD_NOT_THIS_SESSION = 5 // no save made since the game started
    const val QUICK_LOAD_MOVED_PAST = 6 // the game has moved past the save; nothing was changed

    /**
     * Scans all live guest process memory for a 4-byte value. [valueType] 0 reads memory as a
     * signed integer and looks for an exact match of [needleValue]. [valueType] 1 reads it as a
     * float: [needleValue] then holds the float's raw bits, and any stored float within
     * [tolerance] of it matches (0 = bit-exact).
     * Returns matches interleaved as [addr0, value0, addr1, value1, ...] (capped internally to
     * a sane limit; each value is the raw 32 bits) - decode with CheatOverlay's decodeMatches().
     */
    external fun cheatSearch(needleValue: Int, valueType: Int, tolerance: Float): LongArray

    /**
     * "Blind search" step 1: snapshots current guest memory for a later [cheatCompareSnapshot]
     * call, for when the caller doesn't know an exact value to search for yet.
     */
    external fun cheatTakeSnapshot()

    /**
     * "Blind search" step 2: compares live memory against the snapshot taken by
     * [cheatTakeSnapshot] and returns every address whose value satisfies [comparison]
     * (0 = increased, 1 = decreased, 2 = unchanged) relative to the snapshotted value there.
     * Interleaved like [cheatSearch]. Consumes the snapshot - only callable once per
     * [cheatTakeSnapshot]. [valueType] is 0 for integers, 1 for floats.
     */
    external fun cheatCompareSnapshot(comparison: Int, valueType: Int): LongArray

    /**
     * Narrows [candidates] (interleaved like [cheatSearch]'s return) down to just the ones
     * that still qualify - the "next scan" step. If [comparison] is -1, keeps addresses whose
     * current value equals [needleValue] (exact-match refine). Otherwise [needleValue] is
     * ignored and each candidate's current value is compared against its OWN previous value
     * (0 = increased, 1 = decreased, 2 = unchanged) - this is what lets these refines chain
     * across multiple passes, not just once right after a blind search. [valueType] and
     * [tolerance] work as in [cheatSearch].
     */
    external fun cheatRefine(
        candidates: LongArray,
        comparison: Int,
        needleValue: Int,
        valueType: Int,
        tolerance: Float
    ): LongArray

    /**
     * Reads [length] bytes of live guest process memory at [address].
     * Returns an empty array on failure.
     */
    external fun cheatRead(address: Long, length: Int): ByteArray

    /**
     * Writes [value] into live guest process memory at [address]. [value] must be the same
     * length as whatever it's replacing - this does not shift or resize anything.
     */
    external fun cheatWrite(address: Long, value: ByteArray): Boolean

    /**
     * Freezes [address] to [value]: a native background thread keeps rewriting it (roughly
     * every 100ms) until [cheatClearFrozen] is called, so the game can't change it without a
     * matching cheat code. Calling this again for an already-frozen address just updates the
     * value being held.
     */
    external fun cheatSetFrozen(address: Long, value: Int)

    /** Unfreezes [address]. No-op if it wasn't frozen. */
    external fun cheatClearFrozen(address: Long)

    /** The current frozen (address, value) set, interleaved like [cheatSearch]'s return. */
    external fun cheatGetFrozen(): LongArray

    /**
     * Stops emulation.
     */
    external fun stopEmulation()

    /**
     * Returns true if emulation is running (or is paused).
     */
    external fun isRunning(): Boolean

    /**
     * Returns true if emulation is paused.
     */
    external fun isPaused(): Boolean

    /**
     * Returns the performance stats for the current game
     */
    external fun getPerfStats(): DoubleArray

    /**
     * Returns the current CPU backend.
     */
    external fun getCpuBackend(): String

    /**
     * Returns the current GPU Driver.
     */
    external fun getGpuDriver(): String

    external fun applySettings()

    external fun logSettings()

    enum class CoreError {
        ErrorSystemFiles,
        ErrorSavestate,
        ErrorUnknown
    }

    var coreErrorAlertResult = false
    val coreErrorAlertLock = Object()

    private fun onCoreErrorImpl(title: String, message: String) {
        val emulationActivity = sEmulationActivity.get()
        if (emulationActivity == null) {
            Log.error("[NativeLibrary] EmulationActivity not present")
            return
        }

        val fragment = CoreErrorDialogFragment.newInstance(title, message)
        fragment.show(emulationActivity.supportFragmentManager, "coreError")
    }

    /**
     * Handles a core error.
     *
     * @return true: continue; false: abort
     */
    fun onCoreError(error: CoreError?, details: String): Boolean {
        val emulationActivity = sEmulationActivity.get()
        if (emulationActivity == null) {
            Log.error("[NativeLibrary] EmulationActivity not present")
            return false
        }

        val title: String
        val message: String
        when (error) {
            CoreError.ErrorSystemFiles -> {
                title = emulationActivity.getString(R.string.system_archive_not_found)
                message = emulationActivity.getString(
                    R.string.system_archive_not_found_message,
                    details.ifEmpty { emulationActivity.getString(R.string.system_archive_general) }
                )
            }

            CoreError.ErrorSavestate -> {
                title = emulationActivity.getString(R.string.save_load_error)
                message = details
            }

            CoreError.ErrorUnknown -> {
                title = emulationActivity.getString(R.string.fatal_error)
                message = emulationActivity.getString(R.string.fatal_error_message)
            }

            else -> {
                return true
            }
        }

        // Show the AlertDialog on the main thread.
        emulationActivity.runOnUiThread { onCoreErrorImpl(title, message) }

        // Wait for the lock to notify that it is complete.
        synchronized(coreErrorAlertLock) { coreErrorAlertLock.wait() }

        return coreErrorAlertResult
    }

    @Keep
    @JvmStatic
    fun exitEmulationActivity(resultCode: Int) {
        val Success = 0
        val ErrorNotInitialized = 1
        val ErrorGetLoader = 2
        val ErrorSystemFiles = 3
        val ErrorSharedFont = 4
        val ErrorVideoCore = 5
        val ErrorUnknown = 6
        val ErrorLoader = 7

        val captionId: Int
        var descriptionId: Int
        when (resultCode) {
            ErrorVideoCore -> {
                captionId = R.string.loader_error_video_core
                descriptionId = R.string.loader_error_video_core_description
            }

            else -> {
                captionId = R.string.loader_error_encrypted
                descriptionId = R.string.loader_error_encrypted_roms_description
                if (!reloadKeys()) {
                    descriptionId = R.string.loader_error_encrypted_keys_description
                }
            }
        }

        val emulationActivity = sEmulationActivity.get()
        if (emulationActivity == null) {
            Log.warning("[NativeLibrary] EmulationActivity is null, can't exit.")
            return
        }

        // The game can't run: say why, then close the emulation screen.
        emulationActivity.runOnUiThread {
            showIronLemonDialog(emulationActivity, onDismiss = { emulationActivity.finish() }) { dismiss ->
                IronLemonDialogCard(
                    title = emulationActivity.getString(captionId),
                    buttons = {
                        IronLemonButton(
                            text = emulationActivity.getString(android.R.string.ok),
                            primary = true,
                            height = 44.dp,
                            onClick = dismiss
                        )
                    }
                ) {
                    IronLemonDialogText(AnnotatedString.fromHtml(emulationActivity.getString(descriptionId)))
                }
            }
        }
    }

    fun setEmulationActivity(emulationActivity: EmulationActivity?) {
        Log.debug("[NativeLibrary] Registering EmulationActivity.")
        sEmulationActivity = WeakReference(emulationActivity)
    }

    fun clearEmulationActivity() {
        Log.debug("[NativeLibrary] Unregistering EmulationActivity.")
        sEmulationActivity.clear()
    }

    @Keep
    @JvmStatic
    fun onEmulationStarted() {
        sEmulationActivity.get()!!.onEmulationStarted()
    }

    @Keep
    @JvmStatic
    fun onEmulationStopped(status: Int) {
        sEmulationActivity.get()!!.onEmulationStopped(status)
    }

    @Keep
    @JvmStatic
    fun onProgramChanged(programIndex: Int) {
        sEmulationActivity.get()!!.onProgramChanged(programIndex)
    }

    /**
     * Logs the Yuzu version, Android version and, CPU.
     */
    external fun logDeviceInfo()

    /**
     * Submits inline keyboard text. Called on input for buttons that result text.
     * @param text Text to submit to the inline software keyboard implementation.
     */
    external fun submitInlineKeyboardText(text: String?)

    /**
     * Submits inline keyboard input. Used to indicate keys pressed that are not text.
     * @param key_code Android Key Code associated with the keyboard input.
     */
    external fun submitInlineKeyboardInput(key_code: Int)

    /**
     * Creates a generic user directory if it doesn't exist already
     */
    external fun initializeEmptyUserDirectory()

    /**
     * Gets the launch path for a given applet. It is the caller's responsibility to also
     * set the system's current applet ID before trying to launch the nca given by this function.
     *
     * @param id The applet entry ID
     * @return The applet's launch path
     */
    external fun getAppletLaunchPath(id: Long): String

    /**
     * Sets the system's current applet ID before launching.
     *
     * @param appletId One of the ids in the Service::AM::Applets::AppletId enum
     */
    external fun setCurrentAppletId(appletId: Int)

    /**
     * Sets the cabinet mode for launching the cabinet applet.
     *
     * @param cabinetMode One of the modes that corresponds to the enum in Service::NFP::CabinetMode
     */
    external fun setCabinetMode(cabinetMode: Int)

    /**
     * Checks whether NAND contents are available and valid.
     *
     * @return 'true' if firmware is available
     */
    external fun isFirmwareAvailable(): Boolean

    /**
     * Checks the PatchManager for any addons that are available
     *
     * @param path Path to game file. Can be a [Uri].
     * @param programId String representation of a game's program ID
     * @return Array of available patches
     */
    external fun getPatchesForFile(path: String, programId: String): Array<Patch>?

    /**
     * The build ID a game's cheats are keyed by (its main executable's, with the installed update),
     * as the 16 hex digits cheat files are named after; empty if it can't be read. The running
     * game's when [programId] is running.
     */
    external fun getCheatBuildId(path: String, programId: String): String

    /**
     * The Atmosphere cheats loaded for [buildId], one string per cheat: mod folder ("" for a loose
     * cheat_*.txt file), name, enabled (1/0) and master code (1/0), separated by \u001F.
     */
    external fun getCheats(programId: String, buildId: String): Array<String>

    /**
     * Switches one cheat of a mod folder on or off; applied at once when the game is running.
     * Returns 0 when saved, 1 when it could not be saved, 2 when switching it on would overflow the
     * cheat engine (it is left off).
     */
    external fun setCheatEnabled(
        programId: String,
        buildId: String,
        folder: String,
        name: String,
        enabled: Boolean
    ): Int

    /** Reads the cheats again if [programId] is running (after a cheat folder was removed). */
    external fun reloadCheats(programId: String)

    /**
     * Saves pasted cheat codes for [buildId] in a new "Lemon cheats" mod folder. Returns how many
     * cheats the text holds; 0 if none and -1 if they could not be saved, and then nothing is.
     * With a [folder] (downloaded cheats), they replace that folder's cheats for [buildId] instead,
     * keeping the switches of the ones it had.
     */
    external fun addCheats(
        programId: String,
        buildId: String,
        text: String,
        folder: String
    ): Int

    /**
     * Removes an update for a given [programId]
     * @param programId String representation of a game's program ID
     */
    external fun removeUpdate(programId: String)

    /**
     * Removes all DLC for a  [programId]
     * @param programId String representation of a game's program ID
     */
    external fun removeDLC(programId: String)

    /**
     * Removes a mod installed for a given [programId]
     * @param programId String representation of a game's program ID
     * @param name The name of a mod as given by [getPatchesForFile]. This corresponds with the name
     * of the mod's directory in a game's load folder.
     */
    external fun removeMod(programId: String, name: String)

    /**
     * Verifies all installed content
     * @param callback UI callback for verification progress. Return true in the callback to cancel.
     * @return Array of content that failed verification. Successful if empty.
     */
    external fun verifyInstalledContents(
        callback: (max: Long, progress: Long) -> Boolean
    ): Array<String>

    /**
     * Verifies the contents of a game
     * @param path String path to a game
     * @param callback UI callback for verification progress. Return true in the callback to cancel.
     * @return Int that is meant to be converted to a [GameVerificationResult]
     */
    external fun verifyGameContents(
        path: String,
        callback: (max: Long, progress: Long) -> Boolean
    ): Int

    /**
     * Gets the save location for a specific game
     *
     * @param programId String representation of a game's program ID
     * @return Save data path that may not exist yet
     */
    external fun getSavePath(programId: String): String

    /**
     * Gets the root save directory for the default profile as either
     * /user/save/account/<user id raw string> or /user/save/000...000/<user id>
     *
     * @param future If true, returns the /user/save/account/... directory
     * @return Save data path that may not exist yet
     */
    external fun getDefaultProfileSaveDataRoot(future: Boolean): String

    /**
     * Adds a file to the manual filesystem provider in our EmulationSession instance
     * @param path Path to the file we're adding. Can be a string representation of a [Uri] or
     * a normal path
     */
    external fun addFileToFilesystemProvider(path: String)

    /**
     * Clears all files added to the manual filesystem provider in our EmulationSession instance
     */
    external fun clearFilesystemProvider()

    /**
     * Checks if all necessary keys are present for decryption
     */
    external fun areKeysPresent(): Boolean
}
