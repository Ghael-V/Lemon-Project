// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialSharedAxis
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.model.HomeViewModel
import dev.lemon.lemon_emu.online.OnlineRoom
import dev.lemon.lemon_emu.online.OnlineSession
import dev.lemon.lemon_emu.ui.modern.ModernOnlineScreen
import dev.lemon.lemon_emu.ui.modern.OnlineActions
import dev.lemon.lemon_emu.ui.modern.OnlineUiState

class OnlineFragment : Fragment() {
    private val homeViewModel: HomeViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        OnlineSession.start()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val actions = OnlineActions(
            onBack = { findNavController().popBackStack() },
            onCreate = { players, password ->
                OnlineSession.create(players, password.ifEmpty { null }, null, null)
            },
            onJoin = { code, password -> OnlineSession.join(code, password.ifEmpty { null }) },
            onLeave = { OnlineSession.leave() },
            onCopyCode = ::copyCode,
            onNickname = { OnlineSession.nickname = it },
            onServer = { OnlineSession.serverUrl = it }
        )
        setContent {
            val room by OnlineSession.room.collectAsState()
            val busy by OnlineSession.busy.collectAsState()
            val notice by OnlineSession.notice.collectAsState()
            val connection by OnlineRoom.state.collectAsState()
            val members by OnlineRoom.members.collectAsState()
            ModernOnlineScreen(
                OnlineUiState(
                    room = room,
                    connection = connection,
                    members = members,
                    busy = busy,
                    notice = notice,
                    nickname = OnlineSession.nickname,
                    server = OnlineSession.serverUrl
                ),
                actions
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setStatusBarShadeVisibility(visible = false)
    }

    private fun copyCode(code: String) {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.online_room_code), code))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
    }
}
