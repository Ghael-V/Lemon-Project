// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.app.Dialog
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.ironlemon.ironlemon_emulator.R
import androidx.compose.ui.unit.sp
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonColors
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonIconButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonType
import dev.ironlemon.ironlemon_emulator.ui.modern.lemonInteractive
import dev.ironlemon.ironlemon_emulator.utils.DriverDownloader
import dev.ironlemon.ironlemon_emulator.utils.FullscreenHelper
import dev.ironlemon.ironlemon_emulator.utils.GpuDriverHelper
import dev.ironlemon.ironlemon_emulator.utils.Log
import java.io.File

/**
 * Lists the GPU drivers published by [DriverDownloader.sources] and installs the one picked, the
 * same way as a zip chosen with Install: it ends up in the driver list of [DriverManagerFragment].
 */
class DriverDownloadDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply {
            window?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val metrics = resources.displayMetrics
            window.setLayout(
                (metrics.widthPixels * 0.86f).toInt(),
                (metrics.heightPixels * 0.88f).toInt()
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.6f)
            FullscreenHelper.hideSystemBars(window)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            DriverDownloadScreen(
                supported = GpuDriverHelper.supportsCustomDriverLoading(),
                onInstalled = { path ->
                    (parentFragment as? DriverManagerFragment)?.onDriverDownloaded(path)
                },
                onClose = { dismiss() }
            )
        }
    }

    companion object {
        const val TAG = "DriverDownloadDialogFragment"
    }
}

private sealed interface ReleasesState {
    data object Loading : ReleasesState
    data class Loaded(val releases: List<DriverDownloader.Release>) : ReleasesState
    data object Failed : ReleasesState
}

private sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val asset: String) : InstallState
    data class Done(val asset: String) : InstallState
    data class Failed(val message: Int) : InstallState
}

/** Checks a downloaded zip like Install does, and keeps it with the other drivers. */
private suspend fun keepDriver(file: File): Pair<String?, Int?> = withContext(Dispatchers.IO) {
    val metadata = GpuDriverHelper.getMetadataFromZip(file)
    when {
        metadata.name == null -> {
            file.delete()
            null to R.string.driver_download_invalid
        }

        metadata.minApi > Build.VERSION.SDK_INT -> {
            file.delete()
            null to R.string.driver_download_needs_newer_android
        }

        else -> {
            GpuDriverHelper.initializeDirectories()
            val target = File(GpuDriverHelper.driverStoragePath, file.name)
            if (target.exists()) {
                file.delete()
                null to R.string.driver_already_installed
            } else if (!file.renameTo(target) && runCatching {
                    file.copyTo(target)
                    file.delete()
                }.isFailure
            ) {
                null to R.string.driver_download_failed
            } else {
                target.path to null
            }
        }
    }
}

@Composable
private fun DriverDownloadScreen(
    supported: Boolean,
    onInstalled: (String) -> Unit,
    onClose: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(IronLemonColors.Background)
            .border(1.dp, IronLemonColors.gradient(0.5f), shape)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(stringResource(R.string.driver_download_title), style = IronLemonType.Display)
                BasicText(
                    stringResource(R.string.driver_download_description),
                    style = IronLemonType.Body
                )
            }
            IronLemonIconButton(
                iconRes = R.drawable.ic_close,
                description = stringResource(R.string.close),
                onClick = onClose
            )
        }
        Spacer(Modifier.height(16.dp))
        if (!supported) {
            BasicText(
                stringResource(R.string.custom_driver_not_supported_description),
                style = IronLemonType.Body.copy(color = IronLemonColors.Text)
            )
            return@Column
        }
        DriverSources(onInstalled)
    }
}

@Composable
private fun DriverSources(onInstalled: (String) -> Unit) {
    val sources = remember {
        DriverDownloader.sourcesFor(
            adreno = GpuDriverHelper.hasKgslDevice(),
            // The GPU model; unknown yet, every build for this kind of GPU is listed.
            gpuName = GpuDriverHelper.cachedSystemDriverInfo()?.getOrNull(2)
        )
    }
    if (sources.isEmpty()) {
        BasicText(
            stringResource(R.string.driver_download_no_build),
            style = IronLemonType.Body.copy(fontSize = 15.sp)
        )
        return
    }
    var source by remember { mutableStateOf(sources.first()) }
    var releases by remember { mutableStateOf<ReleasesState>(ReleasesState.Loading) }
    var reload by remember { mutableStateOf(0) }
    var install by remember { mutableStateOf<InstallState>(InstallState.Idle) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(source, reload) {
        releases = ReleasesState.Loading
        releases = try {
            ReleasesState.Loaded(DriverDownloader.fetchReleases(source))
        } catch (e: Exception) {
            Log.warning("[DriverDownloader] ${source.repository}: $e")
            ReleasesState.Failed
        }
    }

    fun start(asset: DriverDownloader.Asset) {
        if (install is InstallState.Downloading) return
        install = InstallState.Downloading(asset.name)
        progress = 0f
        job = scope.launch {
            install = try {
                val file = DriverDownloader.download(asset, context.cacheDir.resolve("drivers")) {
                    progress = it
                }
                val (path, error) = keepDriver(file)
                if (path != null) {
                    onInstalled(path)
                    InstallState.Done(asset.name)
                } else {
                    InstallState.Failed(error ?: R.string.driver_download_failed)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.warning("[DriverDownloader] ${asset.url}: $e")
                InstallState.Failed(R.string.driver_download_failed)
            }
        }
    }

    // Sources, as chips (more than fit: they scroll sideways).
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        sources.forEach { candidate ->
            val selected = candidate == source
            val chipShape = RoundedCornerShape(20.dp)
            Box(
                modifier = Modifier
                    .lemonInteractive(
                        shape = chipShape,
                        cornerRadius = 20.dp,
                        selected = selected,
                        onClick = { source = candidate }
                    )
                    .clip(chipShape)
                    .then(
                        if (selected) {
                            Modifier.background(IronLemonColors.Gradient)
                        } else {
                            Modifier.background(IronLemonColors.SurfaceRaised)
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                BasicText(
                    candidate.name,
                    style = IronLemonType.Button.copy(
                        color = if (selected) IronLemonColors.OnLemon else IronLemonColors.Text
                    )
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    BasicText(stringResource(source.description), style = IronLemonType.Body)
    Spacer(Modifier.height(12.dp))

    when (val state = install) {
        is InstallState.Downloading -> StatusLine(
            stringResource(R.string.driver_download_downloading, state.asset),
            progress
        )

        is InstallState.Done -> StatusLine(stringResource(R.string.driver_download_done, state.asset))
        is InstallState.Failed -> StatusLine(stringResource(state.message), error = true)
        InstallState.Idle -> {}
    }

    when (val state = releases) {
        ReleasesState.Loading -> BasicText(
            stringResource(R.string.driver_download_loading),
            style = IronLemonType.Body
        )

        ReleasesState.Failed -> Column {
            BasicText(
                stringResource(R.string.driver_download_list_failed),
                style = IronLemonType.Body.copy(color = IronLemonColors.Text)
            )
            Spacer(Modifier.height(12.dp))
            IronLemonButton(
                text = stringResource(R.string.driver_download_retry),
                iconRes = R.drawable.ic_refresh,
                onClick = { reload++ }
            )
        }

        is ReleasesState.Loaded -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(state.releases, key = { it.tag }) { release ->
                ReleaseCard(release, busy = install is InstallState.Downloading, onPick = ::start)
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, progress: Float? = null, error: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        BasicText(
            text,
            style = IronLemonType.Body.copy(
                color = if (error) IronLemonColors.Red else IronLemonColors.Text
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (progress != null) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(IronLemonColors.SurfaceRaised)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (progress < 0f) 1f else progress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(
                            if (progress < 0f) IronLemonColors.gradient(0.4f) else IronLemonColors.Gradient
                        )
                )
            }
        }
    }
}

@Composable
private fun ReleaseCard(
    release: DriverDownloader.Release,
    busy: Boolean,
    onPick: (DriverDownloader.Asset) -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(IronLemonColors.Surface)
            .border(1.dp, IronLemonColors.Outline, shape)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                release.title,
                style = IronLemonType.Heading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            BasicText(
                if (release.prerelease) {
                    stringResource(R.string.driver_download_prerelease, release.date)
                } else {
                    release.date
                },
                style = IronLemonType.Label
            )
        }
        release.assets.forEach { asset ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        asset.name,
                        style = IronLemonType.Body.copy(color = IronLemonColors.Text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    BasicText(
                        Formatter.formatShortFileSize(LocalContext.current, asset.size),
                        style = IronLemonType.Body
                    )
                }
                Spacer(Modifier.width(12.dp))
                IronLemonButton(
                    text = stringResource(R.string.driver_download_install),
                    iconRes = R.drawable.ic_download,
                    primary = !busy,
                    height = 44.dp,
                    onClick = { if (!busy) onPick(asset) }
                )
            }
        }
    }
}
