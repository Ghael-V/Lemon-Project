// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.utils.NativeConfig
import java.util.concurrent.atomic.AtomicBoolean

class AddonViewModel : ViewModel() {
    private val _patchList = MutableStateFlow(mutableListOf<Patch>())
    val addonList get() = _patchList.asStateFlow()

    private val _showModInstallPicker = MutableStateFlow(false)
    val showModInstallPicker get() = _showModInstallPicker.asStateFlow()

    private val _showModNoticeDialog = MutableStateFlow(false)
    val showModNoticeDialog get() = _showModNoticeDialog.asStateFlow()

    private val _addonToDelete = MutableStateFlow<Patch?>(null)
    val addonToDelete = _addonToDelete.asStateFlow()

    var game: Game? = null

    private val isRefreshing = AtomicBoolean(false)

    // An update or DLC was removed while the screen was open: the game list shows stale versions.
    private var removedContent = false

    fun onOpenAddons(game: Game) {
        this.game = game
        refreshAddons()
    }

    fun refreshAddons() {
        if (isRefreshing.get() || game == null) {
            return
        }
        isRefreshing.set(true)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val patchList = (
                    NativeLibrary.getPatchesForFile(game!!.path, game!!.programId)
                        ?: emptyArray()
                    ).toMutableList()
                patchList.sortBy { it.name }
                _patchList.value = patchList
                isRefreshing.set(false)
            }
        }
    }

    fun setAddonToDelete(patch: Patch?) {
        _addonToDelete.value = patch
    }

    fun onDeleteAddon(patch: Patch) {
        when (PatchType.from(patch.type)) {
            PatchType.Update -> NativeLibrary.removeUpdate(patch.programId)
            PatchType.DLC -> NativeLibrary.removeDLC(patch.programId)
            PatchType.Mod -> NativeLibrary.removeMod(patch.programId, patch.name)
        }
        removedContent = true
        refreshAddons()
    }

    /** Saves the enabled add-ons. True if the game list needs a reload to show the change. */
    fun onCloseAddons(): Boolean {
        val listChanged = removedContent
        removedContent = false
        val currentGame = game
        if (_patchList.value.isEmpty() || currentGame == null) {
            return listChanged
        }

        val previouslyDisabled = NativeConfig.getDisabledAddons(currentGame.programId).toSet()
        val disabled = _patchList.value.mapNotNull {
            if (it.enabled) {
                null
            } else {
                it.name
            }
        }
        NativeConfig.setDisabledAddons(currentGame.programId, disabled.toTypedArray())
        NativeConfig.saveGlobalConfig()
        _patchList.value.clear()
        game = null
        return listChanged || disabled.toSet() != previouslyDisabled
    }

    fun showModInstallPicker(install: Boolean) {
        _showModInstallPicker.value = install
    }

    fun showModNoticeDialog(show: Boolean) {
        _showModNoticeDialog.value = show
    }
}
