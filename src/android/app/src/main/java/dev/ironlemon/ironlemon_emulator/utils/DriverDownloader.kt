// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.utils

import androidx.annotation.StringRes
import dev.ironlemon.ironlemon_emulator.R
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
 * or the user is sent. Adreno GPUs get the Turnip/Qualcomm builds, Mali ones the PanVK builds.
 */
object DriverDownloader {
    /**
     * A repository whose releases hold drivers. [name] is the project's own name; what it is comes
     * from [description], a string resource. [assetSuffix] picks the driver files among a release's
     * downloads (null: every zip). PanVK builds ([forMali]) are made for one Mali GPU ([forGpu]).
     */
    data class Source(
        val name: String,
        @StringRes val description: Int,
        val repository: String,
        val assetSuffix: String? = null,
        val forMali: Boolean = false,
        val forGpu: String? = null
    )

    data class Asset(val name: String, val url: String, val size: Long)

    data class Release(
        val title: String,
        val tag: String,
        val date: String,
        val prerelease: Boolean,
        val assets: List<Asset>
    )

    /** The repositories Lemon downloads from, Lemon's own Lemon-Ade always first. */
    val allSources = listOf(
        Source("Lemon-Ade Turnip", R.string.driver_source_lemon_ade, "Ghael-V/Turnip-Lemon-Ade"),
        Source("Mr. Purple Turnip", R.string.driver_source_purple, "MrPurple666/purple-turnip"),
        Source("GameHub Adreno 8xx", R.string.driver_source_gamehub, "crueter/GameHub-8Elite-Drivers"),
        Source("KIMCHI Turnip", R.string.driver_source_kimchi, "K11MCH1/AdrenoToolsDrivers"),
        Source("Weab-Chan Freedreno", R.string.driver_source_weab_chan, "Weab-chan/freedreno_turnip-CI"),
        Source(
            "Whitebelyash Turnip",
            R.string.driver_source_whitebelyash,
            "whitebelyash/freedreno_turnip-CI"
        ),
        Source("StevenMXZ Turnip", R.string.driver_source_stevenmxz, "StevenMXZ/Adreno-Tools-Drivers"),
        Source("Balemuni Apex Turnip", R.string.driver_source_balemuni, "Balemuni/Balemunis-Aurora"),
        // Mesa PanVK on the Mali kernel driver. The first repository also publishes bare libraries;
        // only its adrenotools zip is the Android driver package.
        Source(
            "PanVK G615",
            R.string.driver_source_panvk_g615,
            "zenithblue-oss/panvk-kbase-android",
            assetSuffix = "-adrenotools.zip",
            forMali = true,
            forGpu = "G615"
        ),
        Source(
            "PanVK G720",
            R.string.driver_source_panvk_g720,
            "wonderkast02/panvk-g720-kbase-csf",
            forMali = true,
            forGpu = "G720"
        ),
        Source(
            "PanVK G52",
            R.string.driver_source_panvk_g52,
            "LukeValen/panvk-mali-g52",
            forMali = true,
            forGpu = "G52"
        )
    )

    /**
     * The sources for this device: the Turnip and Qualcomm builds on Adreno, and on Mali the PanVK
     * builds for its GPU when [gpuName] tells which (a build for another Mali installs but doesn't
     * start games).
     */
    fun sourcesFor(adreno: Boolean, gpuName: String?): List<Source> = allSources.filter { source ->
        source.forMali != adreno && (
            source.forGpu == null || gpuName == null ||
                gpuName.contains(source.forGpu, ignoreCase = true)
            )
    }

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
                .filter {
                    it.optString("name").endsWith(source.assetSuffix ?: ".zip", ignoreCase = true)
                }
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
