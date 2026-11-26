// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import dev.ironlemon.ironlemon_emulator.HomeNavigationDirections
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.GamesViewModel
import dev.ironlemon.ironlemon_emulator.online.OnlineInvite
import dev.ironlemon.ironlemon_emulator.online.OnlineRoom
import dev.ironlemon.ironlemon_emulator.online.OnlineSession
import dev.ironlemon.ironlemon_emulator.ui.modern.ModernOnlineScreen
import dev.ironlemon.ironlemon_emulator.ui.modern.OnlineActions
import dev.ironlemon.ironlemon_emulator.ui.modern.OnlineUiState

/**
 * The Lemon Online screen, wired to [OnlineSession]. Shared by the library's Online screen
 * ([OnlineFragment]) and the in-game one ([OnlineDialogFragment], [inGame]); [onBack] closes whichever
 * it is.
 */
internal fun Fragment.onlineView(inGame: Boolean = false, onBack: () -> Unit): ComposeView =
    ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val actions = OnlineActions(
            onBack = onBack,
            onCreate = { players, password, cheats, public, name ->
                // A room created while a game runs is for that game (OnlineSession fills it in).
                OnlineSession.create(
                    players,
                    password.ifEmpty { null },
                    cheats,
                    null,
                    null,
                    public,
                    name.ifBlank { null }
                )
            },
            onJoin = { code, password -> OnlineSession.join(code, password.ifEmpty { null }) },
            onLeave = { OnlineSession.leave() },
            onCopyCode = { copyRoomCode(it) },
            onNickname = { OnlineSession.nickname = it },
            onServer = { OnlineSession.serverUrl = it },
            onEnable = { OnlineSession.enable(it) },
            onDeleteData = { OnlineSession.deleteData() },
            onPrivacyPolicy = {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.online_privacy_policy_url)))
                )
            },
            onAddFriend = { OnlineSession.addFriend(it) },
            onAnswerRequest = { code, accept -> OnlineSession.answerFriendRequest(code, accept) },
            onRemoveFriend = { OnlineSession.removeFriend(it) },
            onBlock = { OnlineSession.block(it) },
            onUnblock = { OnlineSession.unblock(it) },
            onAppearOffline = { OnlineSession.setAppearOffline(it) },
            onHideGame = { OnlineSession.setHideGame(it) },
            onInvite = { OnlineSession.inviteFriend(it) },
            onAcceptInvite = { acceptInvite(it, inGame) },
            onDeclineInvite = { OnlineSession.declineInvite(it) },
            onRefreshLobby = { OnlineSession.refreshLobby() },
            onJoinPublic = { room, password ->
                OnlineSession.joinPublic(room, password.ifEmpty { null })
            },
            onReport = { code, block -> OnlineSession.report(code, block) },
            onReportRoom = { roomId, block -> OnlineSession.reportRoom(roomId, block) },
            onBlockRoom = { OnlineSession.blockRoom(it) },
            onUnblockRef = { OnlineSession.unblockRef(it) }
        )
        setContent {
            val room by OnlineSession.room.collectAsState()
            val busy by OnlineSession.busy.collectAsState()
            val notice by OnlineSession.notice.collectAsState()
            val connection by OnlineRoom.state.collectAsState()
            val members by OnlineRoom.members.collectAsState()
            val enabled by OnlineSession.enabled.collectAsState()
            val friends by OnlineSession.friends.collectAsState()
            val privacy by OnlineSession.privacy.collectAsState()
            val invites by OnlineSession.invites.collectAsState()
            val lobby by OnlineSession.lobby.collectAsState()
            ModernOnlineScreen(
                OnlineUiState(
                    room = room,
                    connection = connection,
                    members = members,
                    busy = busy,
                    notice = notice,
                    nickname = OnlineSession.nickname,
                    server = OnlineSession.serverUrl,
                    enabled = enabled,
                    friendCode = OnlineSession.friendCode,
                    friends = friends,
                    privacy = privacy,
                    invites = invites,
                    lobby = lobby
                ),
                actions
            )
        }
    }

/**
 * Accepting an invite also opens its game. From the library the game starts once the service let us
 * into the room; in a game already, or without the game installed, the user is told which one to open.
 */
private fun Fragment.acceptInvite(invite: OnlineInvite, inGame: Boolean) {
    val titleId = invite.titleId
    if (titleId.isEmpty() || OnlineSession.playingTitle.equals(titleId, ignoreCase = true)) {
        OnlineSession.acceptInvite(invite)
        return
    }
    if (inGame) {
        OnlineSession.acceptInvite(invite) { OnlineSession.inform("open_game", invite.titleName) }
        return
    }
    val game = ViewModelProvider(requireActivity())[GamesViewModel::class.java].games.value
        .firstOrNull { it.programIdHex.equals(titleId, ignoreCase = true) }
    if (game == null) {
        OnlineSession.acceptInvite(invite) { OnlineSession.inform("game_missing", invite.titleName) }
        return
    }
    OnlineSession.acceptInvite(invite) {
        if (isAdded) {
            findNavController().navigate(HomeNavigationDirections.actionGlobalEmulationActivity(game, true))
        }
    }
}

private fun Fragment.copyRoomCode(code: String) {
    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.online_room_code), code))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
}
