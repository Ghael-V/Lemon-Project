// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.ironlemon.ironlemon_emulator.features.input.NativeInput
import java.io.File
import dev.ironlemon.ironlemon_emulator.utils.DirectoryInitialization
import dev.ironlemon.ironlemon_emulator.utils.DocumentsTree
import dev.ironlemon.ironlemon_emulator.utils.GpuDriverHelper
import dev.ironlemon.ironlemon_emulator.utils.Log
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun Context.getPublicFilesDir(): File = getExternalFilesDir(null) ?: filesDir

class YuzuApplication : Application() {
    private fun createNotificationChannels() {
        val noticeChannel = NotificationChannel(
            getString(R.string.notice_notification_channel_id),
            getString(R.string.notice_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        noticeChannel.description = getString(R.string.notice_notification_channel_description)
        noticeChannel.setSound(null, null)

        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(noticeChannel)
    }

    /**
     * Writes any uncaught Kotlin/Java exception (thread and stack trace) to log/crash_log.txt
     * before the platform handler ends the process: the native log never sees these crashes.
     * After Lemon's crash logger (Lemon 79bdfe17e8).
     */
    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logDir = File(getPublicFilesDir(), "log").apply { mkdirs() }
                val logFile = File(logDir, CRASH_LOG_FILE)
                // Capped instead of rotated: this file is for the next report, not a history.
                if (logFile.length() > MAX_CRASH_LOG_BYTES) {
                    logFile.delete()
                }
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                FileOutputStream(logFile, true).bufferedWriter().use { writer ->
                    writer.appendLine("=== Crash at $timestamp on thread ${thread.name} ===")
                    writer.appendLine("${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}")
                    writer.appendLine(throwable.stackTraceToString())
                    writer.appendLine()
                }
            } catch (_: Throwable) {
                // Best effort: the logger must never stop the crash from propagating.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        application = this
        documentsTree = DocumentsTree()
        DirectoryInitialization.start()
        GpuDriverHelper.initializeDriverParameters()
        NativeInput.reloadInputDevices()
        NativeLibrary.logDeviceInfo()
        Log.logDeviceInfo()

        createNotificationChannels()
        started.countDown()
    }

    companion object {
        /**
         * Opens once onCreate is done. Android serves a content provider (LibraryProvider) before
         * the application has finished starting, and the native side is not ready until then.
         */
        private val started = CountDownLatch(1)

        /** Waits for onCreate to end; false if it did not within [seconds]. */
        fun awaitStarted(seconds: Long = 15): Boolean = started.await(seconds, TimeUnit.SECONDS)

        const val CRASH_LOG_FILE = "crash_log.txt"
        private const val MAX_CRASH_LOG_BYTES = 256 * 1024L

        var documentsTree: DocumentsTree? = null
        lateinit var application: YuzuApplication

        val appContext: Context
            get() = application.applicationContext
    }
}
