// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.lemon.lemon_emu

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.lemon.lemon_emu.features.input.NativeInput
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import android.content.res.Configuration
import android.os.LocaleList
import dev.lemon.lemon_emu.features.settings.model.IntSetting
import dev.lemon.lemon_emu.utils.DirectoryInitialization
import dev.lemon.lemon_emu.utils.DocumentsTree
import dev.lemon.lemon_emu.utils.GpuDriverHelper
import dev.lemon.lemon_emu.utils.Log
import dev.lemon.lemon_emu.utils.PowerStateUpdater
import dev.lemon.lemon_emu.utils.ControllerNavigationGlobalHook
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.getPublicFilesDir(): File = getExternalFilesDir(null) ?: filesDir

class LemonApplication : Application() {
    private fun createNotificationChannels() {
        val name: CharSequence = getString(R.string.app_notification_channel_name)
        val description = getString(R.string.app_notification_channel_description)
        val foregroundService = NotificationChannel(
            getString(R.string.app_notification_channel_id),
            name,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        foregroundService.description = description
        foregroundService.setSound(null, null)
        foregroundService.vibrationPattern = null

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
        notificationManager.createNotificationChannel(foregroundService)
    }

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logDir = File(getExternalFilesDir(null), "log").apply { mkdirs() }
                val logFile = File(logDir, "crash_log.txt")
                // Cap unbounded growth across repeated crashes in the wild instead of
                // rotating: this file is for the next report, not a full crash history.
                if (logFile.length() > MAX_CRASH_LOG_BYTES) {
                    logFile.delete()
                }
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                FileOutputStream(logFile, true).bufferedWriter().use { writer ->
                    writer.appendLine("=== Crash at $timestamp on thread ${thread.name} ===")
                    writer.appendLine(throwable.stackTraceToString())
                    writer.appendLine()
                }
            } catch (_: Throwable) {
                // Best-effort only; never let the logger itself block the crash from propagating.
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

        // Initialize Freedreno config BEFORE loading native library
        // This ensures GPU driver environment variables are set before adrenotools initializes
        GpuDriverHelper.initializeFreedrenoConfigEarly()

        NativeLibrary.playTimeManagerInit()
        GpuDriverHelper.initializeDriverParameters()
        NativeInput.reloadInputDevices()
        NativeLibrary.logDeviceInfo()
        PowerStateUpdater.start()
        Log.logDeviceInfo()
        ControllerNavigationGlobalHook.install(this)

        createNotificationChannels()
    }

    companion object {
        private const val MAX_CRASH_LOG_BYTES = 256 * 1024

        var documentsTree: DocumentsTree? = null
        lateinit var application: LemonApplication

        val appContext: Context
            get() = application.applicationContext

        private val LANGUAGE_CODES = arrayOf(
            "system", "en", "es", "fr", "de", "it", "pt", "pt-BR", "ru", "ja", "ko",
            "zh-CN", "zh-TW", "pl", "cs", "nb", "hu", "uk", "vi", "id", "ar", "ckb", "fa", "he", "sr"
        )

        fun applyLanguage(context: Context): Context {
            val languageIndex = IntSetting.APP_LANGUAGE.getInt()
            val langCode = if (languageIndex in LANGUAGE_CODES.indices) {
                LANGUAGE_CODES[languageIndex]
            } else {
                "system"
            }

            if (langCode == "system") {
                return context
            }

            val locale = when {
                langCode.contains("-") -> {
                    val parts = langCode.split("-")
                    Locale.Builder().setLanguage(parts[0]).setRegion(parts[1]).build()
                }
                else -> Locale.Builder().setLanguage(langCode).build()
            }

            Locale.setDefault(locale)

            val config = Configuration(context.resources.configuration)
            config.setLocales(LocaleList(locale))

            return context.createConfigurationContext(config)
        }
    }
}
