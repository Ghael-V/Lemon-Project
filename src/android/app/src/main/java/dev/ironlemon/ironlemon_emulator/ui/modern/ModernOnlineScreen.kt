// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.ui.modern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.online.OnlineRoom
import dev.ironlemon.ironlemon_emulator.online.OnlineSession

class OnlineActions(
    val onBack: () -> Unit,
    val onCreate: (maxMembers: Int, password: String) -> Unit,
    val onJoin: (code: String, password: String) -> Unit,
    val onLeave: () -> Unit,
    val onCopyCode: (String) -> Unit,
    val onNickname: (String) -> Unit,
    val onServer: (String) -> Unit
)

class OnlineUiState(
    val room: OnlineSession.Room?,
    val connection: OnlineRoom.State,
    val members: List<OnlineRoom.Member>,
    val busy: Boolean,
    val notice: OnlineSession.Notice?,
    val nickname: String,
    val server: String
)

/** Lemon Online, phase 1: create a room or join one with its code, then see who is in it. */
@Composable
fun ModernOnlineScreen(state: OnlineUiState, actions: OnlineActions) {
    IronLemonPage(title = stringResource(R.string.online_title), onBack = actions.onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                state.notice?.let { NoticeText(it) }
                val room = state.room
                if (room == null) {
                    LobbyContent(state, actions)
                } else {
                    RoomContent(room, state, actions)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun LobbyContent(state: OnlineUiState, actions: OnlineActions) {
    var nickname by remember { mutableStateOf(state.nickname) }
    var players by remember { mutableIntStateOf(8) }
    var createPassword by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var joinPassword by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(state.server) }

    BasicText(stringResource(R.string.online_intro), style = IronLemonType.Body.copy(fontSize = 15.sp))

    Section(stringResource(R.string.online_nickname)) {
        IronLemonTextField(nickname, {
            nickname = it
            actions.onNickname(it)
        })
    }

    Section(stringResource(R.string.online_create_room)) {
        FieldLabel(stringResource(R.string.online_players))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (n in 2..8) {
                IronLemonButton(
                    text = n.toString(),
                    primary = n == players,
                    height = 42.dp,
                    horizontalPadding = 16.dp
                ) { players = n }
            }
        }
        FieldLabel(stringResource(R.string.online_password_optional))
        IronLemonTextField(createPassword, { createPassword = it.take(64) })
        IronLemonButtonRow {
            IronLemonButton(
                text = stringResource(R.string.online_create_room),
                primary = true,
                iconRes = R.drawable.ic_multiplayer
            ) { if (!state.busy) actions.onCreate(players, createPassword) }
        }
    }

    Section(stringResource(R.string.online_join_with_code)) {
        FieldLabel(stringResource(R.string.online_room_code))
        IronLemonTextField(code, { value ->
            code = value.uppercase().filter { it.isLetterOrDigit() }.take(6)
        })
        FieldLabel(stringResource(R.string.online_password_optional))
        IronLemonTextField(joinPassword, { joinPassword = it.take(64) })
        IronLemonButtonRow {
            IronLemonButton(text = stringResource(R.string.online_join), primary = true) {
                if (!state.busy && code.length == 6) actions.onJoin(code, joinPassword)
            }
        }
    }

    if (state.busy) {
        BasicText(stringResource(R.string.online_connecting), style = IronLemonType.Button)
    }

    // Testing stage: the service runs on a PC in the same network until it has its own address.
    Section(stringResource(R.string.online_server)) {
        IronLemonTextField(server, {
            server = it
            actions.onServer(it)
        })
    }
}

@Composable
private fun RoomContent(room: OnlineSession.Room, state: OnlineUiState, actions: OnlineActions) {
    val connected = state.connection == OnlineRoom.State.Joined ||
        state.connection == OnlineRoom.State.Moderator
    Section(stringResource(R.string.online_room_code)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText(
                room.code,
                style = IronLemonType.Display.copy(fontSize = 40.sp, letterSpacing = 6.sp),
                modifier = Modifier.weight(1f)
            )
            IronLemonIconButton(R.drawable.ic_share, stringResource(R.string.online_copy_code)) {
                actions.onCopyCode(room.code)
            }
        }
        BasicText(
            stringResource(if (connected) R.string.online_connected else R.string.online_connecting),
            style = IronLemonType.Body.copy(
                color = if (connected) IronLemonColors.Lemon else IronLemonColors.TextMuted
            )
        )
    }

    Section(stringResource(R.string.online_members, state.members.size, room.maxMembers)) {
        if (state.members.isEmpty()) {
            BasicText(stringResource(R.string.online_connecting), style = IronLemonType.Body)
        }
        for (member in state.members) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(
                        if (member.game.isEmpty()) IronLemonColors.TextMuted else IronLemonColors.Lemon
                    )
                )
                Column {
                    BasicText(member.nickname, style = IronLemonType.Button)
                    BasicText(
                        member.game.ifEmpty { stringResource(R.string.online_not_playing) },
                        style = IronLemonType.Body.copy(fontSize = 13.sp, color = IronLemonColors.TextMuted)
                    )
                }
            }
        }
    }

    BasicText(stringResource(R.string.online_room_hint), style = IronLemonType.Body.copy(fontSize = 14.sp))
    IronLemonButtonRow {
        IronLemonButton(text = stringResource(R.string.online_leave), onClick = actions.onLeave)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(IronLemonColors.Surface)
            .border(1.dp, IronLemonColors.Outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BasicText(title, style = IronLemonType.Button.copy(fontSize = 17.sp))
        content()
    }
}

@Composable
private fun FieldLabel(text: String) {
    BasicText(text.uppercase(), style = IronLemonType.Label)
}

@Composable
private fun NoticeText(notice: OnlineSession.Notice) {
    val text = when (notice) {
        is OnlineSession.Notice.Service -> when (notice.code) {
            "network" -> stringResource(R.string.online_error_network)
            "room_not_found" -> stringResource(R.string.online_error_room_not_found)
            "wrong_password" -> stringResource(R.string.online_error_wrong_password)
            "rate_limited" -> stringResource(R.string.online_error_rate_limited)
            "no_capacity" -> stringResource(R.string.online_error_no_capacity)
            else -> stringResource(R.string.online_error_service, notice.code)
        }
        is OnlineSession.Notice.Connection -> when (notice.error) {
            OnlineRoom.Error.CouldNotConnect -> stringResource(R.string.online_error_could_not_connect)
            OnlineRoom.Error.RoomIsFull -> stringResource(R.string.online_error_room_full)
            OnlineRoom.Error.LostConnection -> stringResource(R.string.online_error_lost_connection)
            OnlineRoom.Error.HostKicked, OnlineRoom.Error.HostBanned ->
                stringResource(R.string.online_error_removed)
            OnlineRoom.Error.WrongPassword -> stringResource(R.string.online_error_ticket)
            OnlineRoom.Error.WrongVersion -> stringResource(R.string.online_error_version)
            else -> stringResource(R.string.online_error_service, notice.error.name)
        }
    }
    val shape = RoundedCornerShape(18.dp)
    BasicText(
        text,
        style = IronLemonType.Button.copy(color = IronLemonColors.Text),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(IronLemonColors.Red.copy(alpha = 0.22f))
            .border(1.dp, IronLemonColors.Red.copy(alpha = 0.6f), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    )
}
