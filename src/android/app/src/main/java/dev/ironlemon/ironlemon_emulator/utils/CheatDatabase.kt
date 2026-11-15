// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Cheats downloaded from switch-cheats-db (HamletDuFromage), the database AIO-Switch-Updater uses,
 * updated daily from gbatemp and NX-60FPS-RES-GFX-Cheats. It has one JSON file per game, holding the
 * Atmosphere cheats of each build ID: {"<BUILD ID>": {"[name]": "[name]\ncodes…", …}, …}. Only the
 * game's ID is sent, in the address of its file.
 */
object CheatDatabase {
    enum class Kind(val directory: String) {
        GAMEPLAY("cheats"),
        GRAPHICS("cheats_gfx") // 60 FPS, resolution and graphics codes
    }

    sealed interface Result {
        /** Atmosphere cheat text for the build, with the master code first. */
        data class Found(val text: String) : Result

        /** The game is in the database, but not with this build: [count] other builds have cheats. */
        data class OtherVersions(val count: Int) : Result

        /** The database has nothing for this game. */
        data object NotFound : Result

        data object Failed : Result
    }

    private const val BASE_URL =
        "https://raw.githubusercontent.com/HamletDuFromage/switch-cheats-db/master"

    // A game's file is a few KB; no need to read more than this.
    private const val MAX_BYTES = 4L * 1024 * 1024

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** [titleId]: the game's 16-digit hex ID; [buildId]: its main executable's, as Lemon shows it. */
    suspend fun fetch(kind: Kind, titleId: String, buildId: String): Result =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$BASE_URL/${kind.directory}/$titleId.json")
                .build()
            val body = try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> return@withContext Result.NotFound
                        !response.isSuccessful -> return@withContext Result.Failed
                        else -> {
                            val source = response.body?.source() ?: return@withContext Result.Failed
                            source.request(MAX_BYTES + 1)
                            if (source.buffer.size > MAX_BYTES) {
                                return@withContext Result.Failed
                            }
                            source.buffer.readUtf8()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.warning("[CheatDatabase] Could not download ${kind.directory}/$titleId: $e")
                return@withContext Result.Failed
            }
            try {
                val builds = JSONObject(body)
                // Every key but "attribution" is a build ID.
                val otherBuilds = builds.keys().asSequence().count { it != "attribution" }
                val cheats = builds.keys().asSequence()
                    .firstOrNull { it.equals(buildId, ignoreCase = true) }
                    ?.let { builds.getJSONObject(it) }
                    ?: return@withContext Result.OtherVersions(otherBuilds)
                // The master code first, as in an Atmosphere file.
                val names = cheats.keys().asSequence().toList()
                    .sortedBy { if (it.startsWith("{")) 0 else 1 }
                val text = names.joinToString("\n") { cheats.getString(it).trim() + "\n" }
                if (text.isBlank()) Result.OtherVersions(otherBuilds - 1) else Result.Found(text)
            } catch (e: Exception) {
                Log.warning("[CheatDatabase] Unreadable ${kind.directory}/$titleId: $e")
                Result.Failed
            }
        }
}
