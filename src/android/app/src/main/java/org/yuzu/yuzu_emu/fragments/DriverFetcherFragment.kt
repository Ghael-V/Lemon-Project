// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.widget.Toast
import androidx.core.net.toUri
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.DialogProgressBinding
import org.yuzu.yuzu_emu.databinding.FragmentDriverFetcherBinding
import org.yuzu.yuzu_emu.features.fetcher.DriverGroupAdapter
import org.yuzu.yuzu_emu.model.DriverViewModel
import org.yuzu.yuzu_emu.model.HomeViewModel
import org.yuzu.yuzu_emu.utils.FileUtil
import org.yuzu.yuzu_emu.utils.GpuDriverHelper
import org.yuzu.yuzu_emu.utils.ViewUtils.updateMargins
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.getValue

class DriverFetcherFragment : Fragment() {
    private var _binding: FragmentDriverFetcherBinding? = null
    private val binding get() = _binding!!

    private val client = OkHttpClient()

    private val gpuModel: String?
        get() = GpuDriverHelper.hookLibPath?.let { GpuDriverHelper.getGpuModel(hookLibPath = it) }

    private val adrenoModel: Int
        get() = parseAdrenoModel()

    private val recommendedDriver: String
        get() = driverMap.firstOrNull { adrenoModel in it.first }?.second ?: "Unsupported"

    enum class SortMode {
        Default, PublishTime,
    }

    private data class DriverRepo(
        val name: String = "",
        val path: String = "",
        val sort: Int = 0,
        val useTagName: Boolean = false,
        val sortMode: SortMode = SortMode.Default
    )

    private val repoList: List<DriverRepo> = listOf(
        DriverRepo("Mr. Purple Turnip", "MrPurple666/purple-turnip", 0),
        DriverRepo("GameHub Adreno 8xx", "crueter/GameHub-8Elite-Drivers", 1),
        DriverRepo("KIMCHI Turnip", "K11MCH1/AdrenoToolsDrivers", 2, true, SortMode.PublishTime),
        DriverRepo("Weab-Chan Freedreno", "Weab-chan/freedreno_turnip-CI", 3),
        DriverRepo("Whitebelyash Turnip", "whitebelyash/freedreno_turnip-CI", sort=4, false, SortMode.PublishTime),
    )

    private val driverMap = listOf(
        IntRange(Integer.MIN_VALUE, 9) to "Unsupported",
        IntRange(10, 99) to "KIMCHI Latest", // Special case for Adreno Axx
        IntRange(100, 599) to "Unsupported",
        IntRange(600, 639) to "Mr. Purple EOL-24.3.4",
        IntRange(640, 699) to "Mr. Purple T19",
        IntRange(700, 710) to "KIMCHI 25.2.0_r5",
        IntRange(711, 799) to "Mr. Purple T23",
        IntRange(800, 899) to "GameHub Adreno 8xx",
        IntRange(900, Int.MAX_VALUE) to "Unsupported"
    )

    private lateinit var driverGroupAdapter: DriverGroupAdapter
    private val driverViewModel: DriverViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by activityViewModels()

    // Populated incrementally by fetchDrivers(); read by the "install recommended" button
    // once every repo has responded.
    private val fetchedDriverGroups = arrayListOf<DriverGroup>()

    private fun parseAdrenoModel(): Int {
        if (gpuModel == null) {
            return 0
        }

        val modelList = gpuModel!!.split(" ")

        // format: Adreno (TM) <ModelNumber>
        if (modelList.size < 3 || modelList[0] != "Adreno") {
            return 0
        }

        val model = modelList[2]

        try {
            // special case for Axx GPUs (e.g. AYANEO Pocket S2)
            // driverMap has specific ranges for this
            if (model.startsWith("A")) {
                return model.substring(1).toInt()
            }

            return model.toInt()
        } catch (e: Exception) {
            // Model parse error, just say unsupported
            e.printStackTrace()
            return 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverFetcherBinding.inflate(inflater)
        binding.badgeRecommendedDriver.text = recommendedDriver
        binding.badgeGpuModel.text = gpuModel

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setStatusBarShadeVisibility(visible = false)
        binding.toolbarDrivers.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.listDrivers.layoutManager = LinearLayoutManager(context)
        driverGroupAdapter = DriverGroupAdapter(requireActivity(), driverViewModel)
        binding.listDrivers.adapter = driverGroupAdapter

        binding.buttonInstallRecommended.isEnabled = false
        binding.buttonInstallRecommended.setOnClickListener { onInstallRecommendedClicked() }

        setInsets()

        fetchDrivers()
    }

    private fun fetchDrivers() {
        binding.loadingIndicator.isVisible = true

        repoList.forEach { driver ->
            val name = driver.name
            val path = driver.path
            val useTagName = driver.useTagName
            val sortMode = driver.sortMode
            val sort = driver.sort

            CoroutineScope(Dispatchers.Main).launch {
                val request =
                    Request.Builder().url("https://api.github.com/repos/$path/releases").build()

                withContext(Dispatchers.IO) {
                    var releases: ArrayList<Release>
                    try {
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IOException(response.body.toString())
                            }

                            val body = response.body?.string() ?: return@withContext
                            releases = Release.fromJsonArray(body, useTagName, sortMode)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            MaterialAlertDialogBuilder(requireActivity()).setTitle(
                                getString(R.string.error_during_fetch)
                            )
                                .setMessage(
                                    "${getString(R.string.failed_to_fetch)} $name:\n${e.message}"
                                )
                                .setPositiveButton(getString(R.string.ok)) { dialog, _ -> dialog.cancel() }
                                .show()

                            releases = ArrayList()
                        }
                    }

                    val group = DriverGroup(
                        name,
                        releases,
                        sort
                    )

                    synchronized(fetchedDriverGroups) {
                        fetchedDriverGroups.add(group)
                        fetchedDriverGroups.sortBy {
                            it.sort
                        }
                    }

                    withContext(Dispatchers.Main) {
                        driverGroupAdapter.updateDriverGroups(fetchedDriverGroups)

                        if (fetchedDriverGroups.size >= repoList.size) {
                            binding.loadingIndicator.isVisible = false
                            binding.buttonInstallRecommended.isEnabled =
                                findRecommendedInstall() != null
                        }
                    }
                }
            }
        }
    }

    // Best-effort match of the recommendedDriver label (e.g. "Mr. Purple T23") back to a
    // specific (release, artifact) pair: the label's prefix identifies the repo/group, and
    // (unless it just says "Latest") the remainder should appear in that release's title/tag.
    // Never installs silently - onInstallRecommendedClicked() always confirms with the user
    // first, since this heuristic isn't guaranteed to be right.
    private fun findRecommendedInstall(): Pair<Release, Artifact>? {
        if (adrenoModel <= 0) return null

        val label = recommendedDriver
        if (label == "Unsupported") return null

        val group = fetchedDriverGroups.firstOrNull { g ->
            val keyword = g.name.removeSuffix(" Turnip").removeSuffix(" Freedreno")
            label.startsWith(keyword)
        } ?: return null

        val keyword = group.name.removeSuffix(" Turnip").removeSuffix(" Freedreno")
        val versionHint = label.removePrefix(keyword).trim()

        val release = if (versionHint.isEmpty() || versionHint.equals("Latest", ignoreCase = true)) {
            group.releases.firstOrNull { it.latest } ?: group.releases.firstOrNull()
        } else {
            group.releases.firstOrNull {
                it.title.contains(versionHint, ignoreCase = true) ||
                    it.tagName.contains(versionHint, ignoreCase = true)
            } ?: group.releases.firstOrNull { it.latest } ?: group.releases.firstOrNull()
        } ?: return null

        val artifact = release.artifacts.firstOrNull() ?: return null
        return release to artifact
    }

    private fun onInstallRecommendedClicked() {
        val (release, artifact) = findRecommendedInstall() ?: run {
            Toast.makeText(
                requireContext(),
                getString(R.string.no_recommended_driver_available),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (GpuDriverHelper.isDriverZipInstalledByName(artifact.name)) {
            Toast.makeText(
                requireContext(),
                getString(R.string.driver_already_installed),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(getString(R.string.install_recommended_driver))
            .setMessage("${release.title}\n${artifact.name}")
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                dialog.dismiss()
                installArtifact(artifact)
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.cancel() }
            .show()
    }

    // Mirrors ReleaseAdapter's per-button download+install flow (kept separate rather than
    // shared, so this shortcut can't regress the manual list below it).
    private fun installArtifact(artifact: Artifact) {
        val context = requireContext()
        val dialogBinding = DialogProgressBinding.inflate(LayoutInflater.from(context))
        dialogBinding.progressBar.isIndeterminate = true
        dialogBinding.title.text = getString(R.string.installing_driver)
        dialogBinding.status.text = getString(R.string.downloading)

        val progressDialog = MaterialAlertDialogBuilder(context)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()
        progressDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val request = Request.Builder()
                    .url(artifact.url)
                    .header("Accept", "application/octet-stream")
                    .build()

                val cacheDir = context.externalCacheDir
                    ?: throw IOException(getString(R.string.failed_cache_dir))
                cacheDir.mkdirs()
                val file = File(cacheDir, artifact.name)

                withContext(Dispatchers.IO) {
                    client.newBuilder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build()
                        .newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IOException("${response.code}")
                            }

                            response.body?.byteStream()?.use { input ->
                                FileOutputStream(file).use { output -> input.copyTo(output) }
                            } ?: throw IOException(getString(R.string.empty_response_body))
                        }
                }

                if (file.length() == 0L) {
                    throw IOException(getString(R.string.driver_empty))
                }

                dialogBinding.status.text = getString(R.string.installing)

                val driverData = GpuDriverHelper.getMetadataFromZip(file)
                val driverPath =
                    "${GpuDriverHelper.driverStoragePath}${FileUtil.getFilename(file.toUri())}"

                if (GpuDriverHelper.copyDriverToInternalStorage(file.toUri())) {
                    driverViewModel.onDriverAdded(Pair(driverPath, driverData))
                    progressDialog.dismiss()
                    Toast.makeText(
                        context,
                        getString(R.string.successfully_installed, driverData.name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    throw IOException(
                        getString(R.string.failed_install_driver, artifact.name)
                    )
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                MaterialAlertDialogBuilder(context)
                    .setTitle(getString(R.string.driver_failed_title))
                    .setMessage(e.message)
                    .setPositiveButton(R.string.ok) { dialog, _ -> dialog.cancel() }
                    .show()
            }
        }
    }

    private fun setInsets() = ViewCompat.setOnApplyWindowInsetsListener(
        binding.root
    ) { _: View, windowInsets: WindowInsetsCompat ->
        val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

        val leftInsets = barInsets.left + cutoutInsets.left
        val rightInsets = barInsets.right + cutoutInsets.right

        binding.toolbarDrivers.updateMargins(left = leftInsets, right = rightInsets)
        binding.listDrivers.updateMargins(left = leftInsets, right = rightInsets)

        binding.listDrivers.updatePadding(
            bottom = barInsets.bottom + resources.getDimensionPixelSize(
                R.dimen.spacing_bottom_list_fab
            )
        )

        windowInsets
    }

    data class Artifact(val url: URL, val name: String)

    data class Release(
        var tagName: String = "",
        var titleName: String = "",
        var title: String = "",
        var body: String = "",
        var artifacts: List<Artifact> = ArrayList(),
        var prerelease: Boolean = false,
        var latest: Boolean = false,
        var publishTime: LocalDateTime = LocalDateTime.now()
    ) {
        companion object {
            fun fromJsonArray(
                jsonString: String,
                useTagName: Boolean,
                sortMode: SortMode
            ): ArrayList<Release> {
                val mapper = jacksonObjectMapper()

                try {
                    val rootNode = mapper.readTree(jsonString)

                    val releases = ArrayList<Release>()

                    var latestRelease: Release? = null

                    if (rootNode.isArray) {
                        rootNode.forEach { node ->
                            val release = fromJson(node, useTagName)

                            if (latestRelease == null && !release.prerelease) {
                                latestRelease = release
                                release.latest = true
                            }

                            releases.add(release)
                        }
                    }

                    when (sortMode) {
                        SortMode.PublishTime -> releases.sortByDescending {
                            it.publishTime
                        }

                        else -> {}
                    }

                    return releases
                } catch (e: Exception) {
                    e.printStackTrace()
                    return ArrayList()
                }
            }

            private fun fromJson(node: JsonNode, useTagName: Boolean): Release {
                try {
                    val tagName = node.get("tag_name").toString().removeSurrounding("\"")
                    val body = node.get("body").toString().removeSurrounding("\"")
                    val prerelease = node.get("prerelease").toString().toBoolean()
                    val titleName = node.get("name").toString().removeSurrounding("\"")

                    val published = node.get("published_at").toString().removeSurrounding("\"")
                    val instantTime: Instant? = Instant.parse(published)
                    val localTime = instantTime?.atZone(ZoneId.systemDefault())?.toLocalDateTime() ?: LocalDateTime.now()

                    val title = if (useTagName) tagName else titleName

                    val assets = node.get("assets")
                    val artifacts = ArrayList<Artifact>()
                    if (assets?.isArray == true) {
                        assets.forEach { subNode ->
                            val urlStr = subNode.get("browser_download_url").toString()
                                .removeSurrounding("\"")

                            val url = URL(urlStr)
                            val name = subNode.get("name").toString().removeSurrounding("\"")

                            val artifact = Artifact(url, name)
                            artifacts.add(artifact)
                        }
                    }

                    return Release(
                        tagName,
                        titleName,
                        title,
                        body,
                        artifacts,
                        prerelease,
                        false,
                        localTime
                    )
                } catch (e: Exception) {
                    // TODO: handle malformed input.
                    e.printStackTrace()
                }

                return Release()
            }
        }
    }

    data class DriverGroup(
        val name: String,
        val releases: ArrayList<Release>,
        val sort: Int
    )
}
