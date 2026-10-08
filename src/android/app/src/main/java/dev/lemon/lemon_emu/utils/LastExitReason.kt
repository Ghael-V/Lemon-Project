// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes down why Android ended Lemon's previous run (out of memory, native crash, killed by the
 * system...). A game that just disappears leaves nothing in Lemon's own log, so the reason is
 * appended to that run's log (lemon_log.txt.old.txt, the one users send) and logged again in the
 * current one.
 */
object LastExitReason {
    fun record(context: Context, logDir: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        val info = try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                ?.firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))
        val text = "Previous run ended at $time: ${reasonName(info.reason)}" +
            " (status ${info.status}, importance ${info.importance}," +
            " pss ${info.pss / 1024} MB, rss ${info.rss / 1024} MB)" +
            (info.description?.let { ": $it" } ?: "")
        Log.info("[ExitReason] $text")

        try {
            val previousLog = File(logDir, "lemon_log.txt.old.txt")
            if (previousLog.exists()) {
                previousLog.appendText("\n[Lemon] $text\n")
            }
        } catch (e: Exception) {
            Log.warning("[ExitReason] Could not append to the previous log: ${e.javaClass.simpleName}")
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed by Android to free memory)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java/Kotlin exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (native crash)"
        ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        // Newer reasons, by value so older SDK stubs still compile.
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN ($reason)"
    }
}
