// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.features.settings.model.StringSetting
import dev.ironlemon.ironlemon_emulator.model.Driver
import dev.ironlemon.ironlemon_emulator.model.DriverViewModel
import dev.ironlemon.ironlemon_emulator.model.HomeViewModel
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonColors
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonIconButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonPage
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonType
import dev.ironlemon.ironlemon_emulator.ui.modern.lemonInteractive
import dev.ironlemon.ironlemon_emulator.utils.FileUtil
import dev.ironlemon.ironlemon_emulator.utils.GpuDriverHelper
import dev.ironlemon.ironlemon_emulator.utils.NativeConfig
import java.io.File
import java.io.IOException

/**
 * The GPU driver list: the system driver and every installed one, the selected one marked. New
 * drivers come from a zip (Install) or from the releases of the driver repositories (Download).
 */
class DriverManagerFragment : Fragment() {
    private val homeViewModel: HomeViewModel by activityViewModels()
    private val driverViewModel: DriverViewModel by activityViewModels()

    private val args by navArgs<DriverManagerFragmentArgs>()

    // What the screen shows, kept in step with the view model's driverData (index 0: system).
    private val drivers = mutableStateListOf<Driver>()

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
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val showUseGlobal by driverViewModel.showClearButton.collectAsState()
            DriverManagerScreen(
                drivers = drivers,
                perGame = NativeConfig.isPerGameConfigLoaded(),
                showUseGlobal = showUseGlobal,
                onBack = { findNavController().popBackStack() },
                onSelect = ::selectDriver,
                onDelete = ::deleteDriver,
                onUseGlobal = ::useGlobalDriver,
                onInstall = { getDriver.launch(arrayOf("application/zip")) },
                onDownload = {
                    DriverDownloadDialogFragment()
                        .show(childFragmentManager, DriverDownloadDialogFragment.TAG)
                }
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setNavigationVisibility(visible = false, animated = true)
        homeViewModel.setStatusBarShadeVisibility(visible = false)

        driverViewModel.onOpenDriverManager(args.game)
        refreshList()
        if (NativeConfig.isPerGameConfigLoaded()) {
            driverViewModel.showClearButton(!StringSetting.DRIVER_PATH.global)
        }
        // The system driver's name may arrive later (it is asked off the main thread).
        viewLifecycleOwner.lifecycleScope.launch {
            driverViewModel.driverList.collect { refreshList() }
        }

        if (!driverViewModel.isInteractionAllowed.value) {
            DriversLoadingDialogFragment().show(
                childFragmentManager,
                DriversLoadingDialogFragment.TAG
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        driverViewModel.onCloseDriverManager(args.game)
    }

    private fun refreshList() {
        drivers.clear()
        drivers.addAll(driverViewModel.driverList.value.map { it.copy() })
    }

    private fun selectDriver(index: Int) {
        if (drivers.getOrNull(index)?.selected != false) {
            return
        }
        drivers.indices.forEach { drivers[it] = drivers[it].copy(selected = it == index) }
        driverViewModel.onDriverSelected(index)
        driverViewModel.showClearButton(!StringSetting.DRIVER_PATH.global)
    }

    private fun deleteDriver(index: Int) {
        if (index <= 0 || index >= drivers.size) {
            return
        }
        val selected = drivers.indexOfFirst { it.selected }
        val newSelected = when {
            selected == index -> 0 // the deleted one was in use: back to the system driver
            selected > index -> selected - 1
            else -> selected.coerceAtLeast(0)
        }
        drivers.removeAt(index)
        drivers.indices.forEach { drivers[it] = drivers[it].copy(selected = it == newSelected) }
        driverViewModel.onDriverRemoved(index, newSelected)
        driverViewModel.showClearButton(!StringSetting.DRIVER_PATH.global)
    }

    private fun useGlobalDriver() {
        StringSetting.DRIVER_PATH.global = true
        driverViewModel.updateDriverList()
        refreshList()
        driverViewModel.showClearButton(false)
    }

    /** A driver zip kept in the driver directory joins the list, selected. False if it was there. */
    private suspend fun addDriver(driverPath: String): Boolean {
        val driverData = GpuDriverHelper.getMetadataFromZip(File(driverPath))
        if (driverViewModel.driverData.any { it.second == driverData }) {
            return false
        }
        driverViewModel.onDriverAdded(Pair(driverPath, driverData))
        withContext(Dispatchers.Main) {
            drivers.indices.forEach { drivers[it] = drivers[it].copy(selected = false) }
            drivers.add(
                Driver(
                    selected = true,
                    title = driverData.name ?: "",
                    version = driverData.version ?: "",
                    description = driverData.description ?: ""
                )
            )
            driverViewModel.showClearButton(!StringSetting.DRIVER_PATH.global)
        }
        return true
    }

    /** A driver the download dialog kept in the driver directory. */
    fun onDriverDownloaded(driverPath: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (!addDriver(driverPath)) {
                File(driverPath).delete()
            }
        }
    }

    private val getDriver =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            if (result == null) {
                return@registerForActivityResult
            }

            ProgressDialogFragment.newInstance(
                requireActivity(),
                R.string.installing_driver,
                false
            ) { _, _ ->
                val driverPath =
                    "${GpuDriverHelper.driverStoragePath}${FileUtil.getFilename(result)}"
                val driverFile = File(driverPath)

                // Ignore file exceptions when a user selects an invalid zip
                try {
                    if (!GpuDriverHelper.copyDriverToInternalStorage(result)) {
                        throw IOException("Driver failed validation!")
                    }
                } catch (_: IOException) {
                    if (driverFile.exists()) {
                        driverFile.delete()
                    }
                    return@newInstance getString(R.string.select_gpu_driver_error)
                }

                if (!addDriver(driverPath)) {
                    return@newInstance getString(R.string.driver_already_installed)
                }
                return@newInstance Any()
            }.show(childFragmentManager, ProgressDialogFragment.TAG)
        }
}

@Composable
private fun DriverManagerScreen(
    drivers: List<Driver>,
    perGame: Boolean,
    showUseGlobal: Boolean,
    onBack: () -> Unit,
    onSelect: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onUseGlobal: () -> Unit,
    onInstall: () -> Unit,
    onDownload: () -> Unit
) {
    IronLemonPage(
        title = stringResource(R.string.gpu_driver_manager),
        onBack = onBack,
        actions = {
            if (perGame && showUseGlobal) {
                IronLemonButton(
                    text = stringResource(R.string.use_global_setting),
                    height = 44.dp,
                    onClick = onUseGlobal
                )
            }
            IronLemonIconButton(
                iconRes = R.drawable.ic_download,
                description = stringResource(R.string.driver_download_title),
                onClick = onDownload
            )
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 420.dp),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(drivers) { index, driver ->
                    DriverCard(
                        driver = driver,
                        deletable = index > 0,
                        onSelect = { onSelect(index) },
                        onDelete = { onDelete(index) }
                    )
                }
            }
            // Download on the left, Install on the right, both always in view.
            IronLemonButton(
                text = stringResource(R.string.driver_download_button),
                iconRes = R.drawable.ic_download,
                primary = true,
                height = 56.dp,
                onClick = onDownload,
                modifier = Modifier.align(Alignment.BottomStart).padding(24.dp)
            )
            IronLemonButton(
                text = stringResource(R.string.install),
                iconRes = R.drawable.ic_add,
                height = 56.dp,
                onClick = onInstall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
            )
        }
    }
}

@Composable
private fun DriverCard(
    driver: Driver,
    deletable: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lemonInteractive(
                shape = shape,
                cornerRadius = 24.dp,
                selected = driver.selected,
                focusScale = 1.02f,
                onClick = onSelect
            )
            .clip(shape)
            .background(IronLemonColors.Surface)
            .border(
                width = if (driver.selected) 2.dp else 1.dp,
                brush = if (driver.selected) {
                    IronLemonColors.Gradient
                } else {
                    SolidColor(IronLemonColors.Outline)
                },
                shape = shape
            )
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The selection mark: a ring, filled with the accent when in use.
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(2.dp, if (driver.selected) IronLemonColors.Orange else IronLemonColors.TextMuted, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (driver.selected) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(IronLemonColors.Gradient))
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                driver.title,
                style = IronLemonType.Heading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (driver.version.isNotBlank()) {
                BasicText(
                    driver.version,
                    style = IronLemonType.Body.copy(color = IronLemonColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (driver.description.isNotBlank()) {
                BasicText(
                    driver.description,
                    style = IronLemonType.Body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (deletable) {
            Spacer(Modifier.width(12.dp))
            IronLemonIconButton(
                iconRes = R.drawable.ic_delete,
                description = stringResource(R.string.delete),
                onClick = onDelete
            )
        }
    }
}
