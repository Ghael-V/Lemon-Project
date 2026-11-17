// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dev.ironlemon.ironlemon_emulator.BuildConfig
import dev.ironlemon.ironlemon_emulator.utils.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Iron Lemon's updates. They are published in Lemon's repository (Forgejo, GitHub mirror as a
 * fallback) with their own tags, `ironlemon-vX.Y.Z`, as prereleases so Lemon's "latest" stays
 * Lemon's. The newest of them is offered when it is newer than this build and carries Iron Lemon's
 * APK. The APK goes through Android's package installer, which always asks the user to confirm.
 */
object AppUpdater {
    data class Release(val tag: String, val notes: String, val apkUrl: String, val apkName: String) {
        /** The version the tag names, as the user sees it ("v0.2.0"). */
        val version: String get() = versionOf(tag)
    }

    private const val FORGEJO = "https://git.lemon-emu.org/api/v1/repos/lemon/Lemon-Project/releases?limit=50"
    private const val GITHUB = "https://api.github.com/repos/Ghael-V/Lemon-Project/releases?per_page=50"

    // Iron Lemon's releases among Lemon's, and the APK they carry for this variant:
    // IronLemon-vX.Y.Z-standard.apk, IronLemon-Spoofed-vX.Y.Z-spoofed.apk.
    private const val TAG_PREFIX = "ironlemon-"
    private const val APK_PREFIX = "IronLemon-"
    private val APK_SUFFIX = BuildConfig.UPDATE_APK_SUFFIX

    private const val PREF_AUTO_CHECK = "update_auto_check"
    private const val INSTALL_ACTION = "dev.ironlemon.UPDATE_INSTALL_STATUS"

    fun isAutoCheckEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_AUTO_CHECK, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_AUTO_CHECK, enabled)
        }

    /** Development builds don't follow releases: their version is not a release's. */
    val canUpdate: Boolean get() = !BuildConfig.DEBUG

    /** The newer release, or null (up to date, nothing for this build, or no answer). Blocking. */
    fun findUpdate(): Release? {
        val newest = fetchNewest(FORGEJO) ?: fetchNewest(GITHUB) ?: return null
        return newest.takeIf { isNewer(versionOf(it.tag), BuildConfig.VERSION_NAME) }
    }

    /** True when the servers answered (so "up to date" can be told apart from "no connection"). */
    @Volatile
    var lastCheckReachedServer = false
        private set

    /** "ironlemon-v0.2.0" -> "v0.2.0". */
    private fun versionOf(tag: String): String = tag.removePrefix(TAG_PREFIX)

    /** The newest Iron Lemon release in the list at [url] that carries its APK, or null. */
    private fun fetchNewest(url: String): Release? {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
            }
            connection.inputStream.bufferedReader().use { reader ->
                lastCheckReachedServer = true
                val releases = JSONArray(reader.readText())
                var newest: Release? = null
                for (i in 0 until releases.length()) {
                    val release = toRelease(releases.getJSONObject(i)) ?: continue
                    if (newest == null || isNewer(versionOf(release.tag), versionOf(newest.tag))) {
                        newest = release
                    }
                }
                newest
            }
        } catch (e: Exception) {
            Log.warning("[Updater] $url: ${e.message}")
            null
        }
    }

    /**
     * An Iron Lemon release with its APK; null for Lemon's own releases, drafts, and releases
     * whose APK isn't there (yet).
     */
    private fun toRelease(json: JSONObject): Release? {
        val tag = json.optString("tag_name")
        if (!tag.startsWith(TAG_PREFIX) || json.optBoolean("draft")) {
            return null
        }
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name.startsWith(APK_PREFIX) && name.endsWith(APK_SUFFIX)) {
                return Release(
                    tag = tag,
                    notes = json.optString("body"),
                    apkUrl = asset.optString("browser_download_url"),
                    apkName = name
                )
            }
        }
        return null
    }

    /**
     * Compares versions number by number ("v2.0.1" > "2.0"); with the same numbers, a release
     * is newer than a prerelease or build with a suffix ("2.0.0" > "2.0.0-nightly.1").
     */
    fun isNewer(tag: String, current: String): Boolean {
        fun parse(v: String): Pair<List<Int>, Boolean> {
            val clean = v.trim().removePrefix("v").removePrefix("V")
            val core = clean.substringBefore('-')
            val numbers = core.split('.').map { it.toIntOrNull() ?: 0 }
            return numbers to clean.contains('-')
        }
        val (t, tSuffix) = parse(tag)
        val (c, cSuffix) = parse(current)
        for (i in 0 until maxOf(t.size, c.size)) {
            val a = t.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return !tSuffix && cSuffix
    }

    /** Downloads the APK into the cache; [progress] gets (total, done) and answers "go on". */
    fun download(context: Context, release: Release, progress: (Long, Long) -> Boolean): File {
        val file = File(context.cacheDir, "update.apk")
        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (!progress(total, done)) {
                        throw InterruptedException("cancelled")
                    }
                }
            }
        }
        return file
    }

    /** Hands the APK to Android's package installer, which asks the user to confirm. */
    fun install(context: Context, apk: File, onError: (String) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            @Suppress("DEPRECATION")
                            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            confirm?.let { c.startActivity(it) }
                        }

                        PackageInstaller.STATUS_SUCCESS -> c.unregisterReceiver(this)
                        else -> {
                            c.unregisterReceiver(this)
                            onError(
                                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                            )
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                IntentFilter(INSTALL_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val intent = Intent(INSTALL_ACTION).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
        }
    }
}
