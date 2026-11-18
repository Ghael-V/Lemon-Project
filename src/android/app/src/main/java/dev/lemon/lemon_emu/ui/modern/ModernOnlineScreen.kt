// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui.modern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.online.OnlineIdentity
import dev.lemon.lemon_emu.online.OnlineRoom
import dev.lemon.lemon_emu.online.OnlineSession

class OnlineActions(
    val onBack: () -> Unit,
    val onCreate: (maxMembers: Int, password: String, cheatsAllowed: Boolean) -> Unit,
    val onJoin: (code: String, password: String) -> Unit,
    val onLeave: () -> Unit,
    val onCopyCode: (String) -> Unit,
    val onNickname: (String) -> Unit,
    val onServer: (String) -> Unit,
    val onEnable: (nickname: String) -> Unit,
    val onDeleteData: () -> Unit,
    val onPrivacyPolicy: () -> Unit
)

class OnlineUiState(
    val room: OnlineSession.Room?,
    val connection: OnlineRoom.State,
    val members: List<OnlineRoom.Member>,
    val busy: Boolean,
    val notice: OnlineSession.Notice?,
    val nickname: String,
    val server: String,
    val enabled: Boolean,
    val friendCode: String?
)

/**
 * Lemon Online, phase 1: create a room or join one with its code, then see who is in it.
 * The same file lives in Lemon and Iron Lemon: it only uses each app's button, icon button,
 * colours and type, and keeps its own page, text field and button row below.
 */
@Composable
fun ModernOnlineScreen(state: OnlineUiState, actions: OnlineActions) {
    OnlinePage(stringResource(R.string.online_title), actions.onBack) {
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
                if (room == null && !state.enabled) {
                    ConsentContent(state, actions)
                } else if (room == null) {
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
    var allowCheats by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var joinPassword by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(state.server) }

    BasicText(stringResource(R.string.online_intro), style = LemonType.Body.copy(fontSize = 15.sp))

    state.friendCode?.let { friendCode ->
        Section(stringResource(R.string.online_friend_code)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BasicText(
                    OnlineIdentity.display(friendCode),
                    style = LemonType.Display.copy(fontSize = 26.sp, letterSpacing = 2.sp),
                    modifier = Modifier.weight(1f)
                )
                LemonIconButton(R.drawable.ic_share, stringResource(R.string.online_copy_friend_code)) {
                    actions.onCopyCode(OnlineIdentity.display(friendCode))
                }
            }
        }
    }

    Section(stringResource(R.string.online_nickname)) {
        OnlineTextField(nickname, {
            nickname = it
            actions.onNickname(it)
        })
    }

    Section(stringResource(R.string.online_create_room)) {
        FieldLabel(stringResource(R.string.online_players))
        PlayerCountPicker(players) { players = it }
        FieldLabel(stringResource(R.string.online_password_optional))
        OnlineTextField(createPassword, { createPassword = it.take(64) })
        OnlineSwitchRow(
            stringResource(R.string.online_allow_cheats),
            stringResource(R.string.online_allow_cheats_hint),
            allowCheats
        ) { allowCheats = it }
        OnlineButtonRow {
            LemonButton(
                text = stringResource(R.string.online_create_room),
                primary = true,
                iconRes = R.drawable.ic_multiplayer
            ) { if (!state.busy) actions.onCreate(players, createPassword, allowCheats) }
        }
    }

    Section(stringResource(R.string.online_join_with_code)) {
        FieldLabel(stringResource(R.string.online_room_code))
        OnlineTextField(code, { value ->
            code = value.uppercase().filter { it.isLetterOrDigit() }.take(6)
        })
        FieldLabel(stringResource(R.string.online_password_optional))
        OnlineTextField(joinPassword, { joinPassword = it.take(64) })
        OnlineButtonRow {
            LemonButton(text = stringResource(R.string.online_join), primary = true) {
                if (!state.busy && code.length == 6) actions.onJoin(code, joinPassword)
            }
        }
    }

    if (state.busy) {
        BasicText(stringResource(R.string.online_connecting), style = LemonType.Button)
    }

    // Testing stage: the address of the service can still be changed by hand.
    Section(stringResource(R.string.online_server)) {
        OnlineTextField(server, {
            server = it
            actions.onServer(it)
        })
    }

    DeleteDataSection(state, actions)
}

/** First run: what Lemon Online keeps, and nothing goes to the service until the button. */
@Composable
private fun ConsentContent(state: OnlineUiState, actions: OnlineActions) {
    var nickname by remember { mutableStateOf(state.nickname) }

    BasicText(stringResource(R.string.online_intro), style = LemonType.Body.copy(fontSize = 15.sp))

    Section(stringResource(R.string.online_enable_title)) {
        BasicText(stringResource(R.string.online_enable_body), style = LemonType.Body.copy(fontSize = 15.sp))
        FieldLabel(stringResource(R.string.online_nickname))
        OnlineTextField(nickname, { nickname = it })
        OnlineButtonRow {
            LemonButton(
                text = stringResource(R.string.online_privacy_policy),
                onClick = actions.onPrivacyPolicy
            )
            LemonButton(text = stringResource(R.string.online_enable_button), primary = true) {
                if (!state.busy) actions.onEnable(nickname)
            }
        }
        if (state.busy) {
            BasicText(stringResource(R.string.online_enable_busy), style = LemonType.Button)
        }
    }
}

/** The privacy policy's promise: everything the service keeps can be erased from here. */
@Composable
private fun DeleteDataSection(state: OnlineUiState, actions: OnlineActions) {
    var confirming by remember { mutableStateOf(false) }
    Section(stringResource(R.string.online_your_data)) {
        if (!confirming) {
            OnlineButtonRow {
                LemonButton(text = stringResource(R.string.online_delete_data)) { confirming = true }
            }
        } else {
            BasicText(stringResource(R.string.online_delete_data_confirm), style = LemonType.Body)
            OnlineButtonRow {
                LemonButton(text = stringResource(android.R.string.cancel)) { confirming = false }
                LemonButton(text = stringResource(R.string.online_delete_data_yes), primary = true) {
                    if (!state.busy) {
                        confirming = false
                        actions.onDeleteData()
                    }
                }
            }
        }
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
                style = LemonType.Display.copy(fontSize = 40.sp, letterSpacing = 6.sp),
                modifier = Modifier.weight(1f)
            )
            LemonIconButton(R.drawable.ic_share, stringResource(R.string.online_copy_code)) {
                actions.onCopyCode(room.code)
            }
        }
        BasicText(
            stringResource(if (connected) R.string.online_connected else R.string.online_connecting),
            style = LemonType.Body.copy(
                color = if (connected) LemonColors.Lemon else LemonColors.TextMuted
            )
        )
        BasicText(
            stringResource(
                if (room.cheatsAllowed) R.string.online_cheats_allowed else R.string.online_cheats_blocked
            ),
            style = LemonType.Body.copy(fontSize = 14.sp, color = LemonColors.TextMuted)
        )
    }

    Section(stringResource(R.string.online_members, state.members.size, room.maxMembers)) {
        if (state.members.isEmpty()) {
            BasicText(stringResource(R.string.online_connecting), style = LemonType.Body)
        }
        for (member in state.members) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(
                        if (member.game.isEmpty()) LemonColors.TextMuted else LemonColors.Lemon
                    )
                )
                Column {
                    BasicText(member.nickname, style = LemonType.Button)
                    BasicText(
                        member.game.ifEmpty { stringResource(R.string.online_not_playing) },
                        style = LemonType.Body.copy(fontSize = 13.sp, color = LemonColors.TextMuted)
                    )
                }
            }
        }
    }

    BasicText(stringResource(R.string.online_room_hint), style = LemonType.Body.copy(fontSize = 14.sp))
    OnlineButtonRow {
        LemonButton(text = stringResource(R.string.online_leave), onClick = actions.onLeave)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LemonColors.Surface)
            .border(1.dp, LemonColors.Outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BasicText(title, style = LemonType.Button.copy(fontSize = 17.sp))
        content()
    }
}

@Composable
private fun FieldLabel(text: String) {
    BasicText(text.uppercase(), style = LemonType.Label)
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
            "unauthorized" -> stringResource(R.string.online_error_unauthorized)
            "identity", "code_taken" -> stringResource(R.string.online_error_identity)
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
        style = LemonType.Button.copy(color = LemonColors.Text),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LemonColors.Red.copy(alpha = 0.22f))
            .border(1.dp, LemonColors.Red.copy(alpha = 0.6f), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    )
}

@Composable
private fun OnlinePage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(LemonColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LemonIconButton(R.drawable.ic_back, stringResource(R.string.back), onClick = onBack)
            BasicText(
                title,
                style = LemonType.Display,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        content()
    }
}

@Composable
private fun OnlineTextField(value: String, onValueChange: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = LemonType.Button,
        cursorBrush = SolidColor(LemonColors.Lemon),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LemonColors.SurfaceRaised)
            .border(1.dp, LemonColors.Outline, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnlineButtonRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) { content() }
}

/** A label with a short explanation and an on/off switch; the whole row toggles it. */
@Composable
private fun OnlineSwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(label, style = LemonType.Button)
            BasicText(hint, style = LemonType.Body.copy(fontSize = 13.sp, color = LemonColors.TextMuted))
        }
        Box(
            Modifier
                .size(width = 52.dp, height = 30.dp)
                .clip(CircleShape)
                .background(if (checked) LemonColors.Lemon else LemonColors.SurfaceRaised)
                .border(1.dp, if (checked) LemonColors.Lemon else LemonColors.Outline, CircleShape)
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (checked) LemonColors.OnLemon else LemonColors.TextMuted)
            )
        }
    }
}

/** 2 to 8 players as round chips; the selected one in the accent colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerCountPicker(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (n in 2..8) {
            val chosen = n == selected
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (chosen) LemonColors.Lemon else LemonColors.SurfaceRaised)
                    .border(1.dp, if (chosen) LemonColors.Lemon else LemonColors.Outline, CircleShape)
                    .clickable { onSelect(n) },
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    n.toString(),
                    style = LemonType.Button.copy(
                        color = if (chosen) LemonColors.OnLemon else LemonColors.Text
                    )
                )
            }
        }
    }
}
