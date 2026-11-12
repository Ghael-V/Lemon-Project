// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * GPU drivers published as GitHub releases, in the zip format adrenotools loads (a meta.json next
 * to the driver library). The releases are read from GitHub's public API; nothing about the device
 * or the user is sent. Only Adreno GPUs can load custom drivers.
 */
object DriverDownloader {
    data class Source(val name: String, val description: String, val repository: String)

    data class Asset(val name: String, val url: String, val size: Long)

    data class Release(
        val title: String,
        val tag: String,
        val date: String,
        val prerelease: Boolean,
        val assets: List<Asset>
    )

    val sources = listOf(
        Source(
            "Adreno Tools Drivers",
            "Qualcomm drivers repackaged and Turnip builds (K11MCH1)",
            "K11MCH1/AdrenoToolsDrivers"
        ),
        Source(
            "Turnip CI",
            "Mesa Turnip built from upstream (Weab-chan)",
            "Weab-chan/freedreno_turnip-CI"
        ),
        Source(
            "Purple Turnip",
            "Turnip with fixes for emulators (MrPurple666)",
            "MrPurple666/purple-turnip"
        )
    )

    private const val API = "https://api.github.com/repos"

    // The list of a repository's releases is a few hundred KB at most.
    private const val MAX_LIST_BYTES = 4L * 1024 * 1024

    // Drivers are 2 to 20 MB; anything far larger is not one.
    private const val MAX_DRIVER_BYTES = 128L * 1024 * 1024

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "IronLemon")
        .header("Accept", "application/vnd.github+json")
        .build()

    /** The newest releases of [source] that have a zip to download, newest first. */
    suspend fun fetchReleases(source: Source): List<Release> = withContext(Dispatchers.IO) {
        val body = client.newCall(request("$API/${source.repository}/releases?per_page=30"))
            .execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("GitHub answered ${response.code}")
                }
                val stream = response.body?.source() ?: throw IOException("Empty answer")
                stream.request(MAX_LIST_BYTES + 1)
                if (stream.buffer.size > MAX_LIST_BYTES) {
                    throw IOException("Answer too large")
                }
                stream.buffer.readUtf8()
            }
        val array = JSONArray(body)
        (0 until array.length()).mapNotNull { i ->
            val release = array.getJSONObject(i)
            if (release.optBoolean("draft")) {
                return@mapNotNull null
            }
            val assetArray = release.optJSONArray("assets") ?: JSONArray()
            val assets = (0 until assetArray.length()).map { assetArray.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".zip", ignoreCase = true) }
                .map {
                    Asset(
                        name = it.getString("name"),
                        url = it.getString("browser_download_url"),
                        size = it.optLong("size")
                    )
                }
            if (assets.isEmpty()) {
                return@mapNotNull null
            }
            val tag = release.optString("tag_name")
            Release(
                title = release.optString("name").ifBlank { tag },
                tag = tag,
                date = release.optString("published_at").take(10),
                prerelease = release.optBoolean("prerelease"),
                assets = assets
            )
        }
    }

    /**
     * Downloads [asset] into [directory] and returns the file. [onProgress] gets the fraction done
     * (0 to 1), or a negative value while the size is unknown. A partial file is deleted on failure.
     */
    suspend fun download(asset: Asset, directory: File, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            // The name comes from the release: keep only its last path part.
            val name = asset.name.substringAfterLast('/').substringAfterLast('\\')
            val target = File(directory, name)
            val partial = File(directory, "$name.part")
            try {
                client.newCall(request(asset.url)).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Download answered ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: asset.size
                    if (total > MAX_DRIVER_BYTES) {
                        throw IOException("Too large for a driver")
                    }
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                done += read
                                if (done > MAX_DRIVER_BYTES) {
                                    throw IOException("Too large for a driver")
                                }
                                output.write(buffer, 0, read)
                                onProgress(if (total > 0) done.toFloat() / total else -1f)
                            }
                        }
                    }
                }
                target.delete()
                if (!partial.renameTo(target)) {
                    throw IOException("Could not keep the download")
                }
                target
            } catch (e: Exception) {
                partial.delete()
                throw e
            }
        }
}
