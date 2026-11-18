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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.online.FriendLists
import dev.lemon.lemon_emu.online.OnlineFriend
import dev.lemon.lemon_emu.online.OnlineIdentity
import dev.lemon.lemon_emu.online.OnlineInvite
import dev.lemon.lemon_emu.online.OnlineRoom
import dev.lemon.lemon_emu.online.OnlineSession
import dev.lemon.lemon_emu.online.PublicRoom

class OnlineActions(
    val onBack: () -> Unit,
    val onCreate: (maxMembers: Int, password: String, cheatsAllowed: Boolean, public: Boolean, name: String) -> Unit,
    val onJoin: (code: String, password: String) -> Unit,
    val onLeave: () -> Unit,
    val onCopyCode: (String) -> Unit,
    val onNickname: (String) -> Unit,
    val onServer: (String) -> Unit,
    val onEnable: (nickname: String) -> Unit,
    val onDeleteData: () -> Unit,
    val onPrivacyPolicy: () -> Unit,
    val onAddFriend: (code: String) -> Unit,
    val onAnswerRequest: (code: String, accept: Boolean) -> Unit,
    val onRemoveFriend: (code: String) -> Unit,
    val onBlock: (code: String) -> Unit,
    val onUnblock: (code: String) -> Unit,
    val onAppearOffline: (Boolean) -> Unit,
    val onHideGame: (Boolean) -> Unit,
    val onInvite: (code: String) -> Unit,
    val onAcceptInvite: (OnlineInvite) -> Unit,
    val onDeclineInvite: (OnlineInvite) -> Unit,
    val onRefreshLobby: () -> Unit,
    val onJoinPublic: (PublicRoom, password: String) -> Unit,
    val onReport: (code: String, block: Boolean) -> Unit
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
    val friendCode: String?,
    val friends: FriendLists,
    val privacy: OnlineSession.Privacy,
    val invites: List<OnlineInvite>,
    val lobby: List<PublicRoom>
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
                if (state.enabled && state.invites.isNotEmpty()) {
                    InvitesSection(state.invites, actions)
                }
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
    var makePublic by remember { mutableStateOf(false) }
    var roomName by remember { mutableStateOf("") }
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

    FriendsSection(state, actions)

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
        OnlineSwitchRow(
            stringResource(R.string.online_public_room),
            stringResource(R.string.online_public_room_hint),
            makePublic
        ) { makePublic = it }
        if (makePublic) {
            FieldLabel(stringResource(R.string.online_room_name_optional))
            OnlineTextField(roomName, { roomName = utf8Prefix(it, 32) })
        }
        OnlineButtonRow {
            LemonButton(
                text = stringResource(R.string.online_create_room),
                primary = true,
                iconRes = R.drawable.ic_multiplayer
            ) { if (!state.busy) actions.onCreate(players, createPassword, allowCheats, makePublic, roomName) }
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

    PublicLobbySection(state, actions)

    // Testing stage: the address of the service can still be changed by hand.
    Section(stringResource(R.string.online_server)) {
        OnlineTextField(server, {
            server = it
            actions.onServer(it)
        })
    }

    PrivacySection(state, actions)

    DeleteDataSection(state, actions)
}

/** Requests to answer, friends with what they are doing, requests we sent, and "add a friend". */
@Composable
private fun FriendsSection(state: OnlineUiState, actions: OnlineActions) {
    val lists = state.friends
    var code by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }

    Section(stringResource(R.string.online_friends, lists.friends.size)) {
        for (request in lists.incoming) {
            PersonRow(request, stringResource(R.string.online_friend_wants_you)) {
                OnlineButtonRow {
                    LemonButton(text = stringResource(R.string.online_decline)) {
                        actions.onAnswerRequest(request.code, false)
                    }
                    LemonButton(text = stringResource(R.string.online_accept), primary = true) {
                        actions.onAnswerRequest(request.code, true)
                    }
                }
            }
        }

        if (lists.friends.isEmpty() && lists.incoming.isEmpty()) {
            BasicText(
                stringResource(R.string.online_no_friends),
                style = LemonType.Body.copy(fontSize = 14.sp, color = LemonColors.TextMuted)
            )
        }
        for (friend in lists.friends.sortedWith(compareBy({ stateOrder(it.state) }, { it.nickname }))) {
            val open = expanded == friend.code
            PersonRow(friend, presenceText(friend), onClick = {
                expanded = if (open) null else friend.code
            }) {
                if (open) {
                    OnlineButtonRow {
                        LemonButton(text = stringResource(R.string.online_block)) {
                            actions.onBlock(friend.code)
                        }
                        LemonButton(text = stringResource(R.string.online_remove_friend)) {
                            actions.onRemoveFriend(friend.code)
                        }
                    }
                }
            }
        }

        for (sent in lists.outgoing) {
            PersonRow(sent, stringResource(R.string.online_request_pending)) {
                OnlineButtonRow {
                    LemonButton(text = stringResource(R.string.online_cancel_request)) {
                        actions.onRemoveFriend(sent.code)
                    }
                }
            }
        }

        FieldLabel(stringResource(R.string.online_add_friend))
        OnlineTextField(code, { value -> code = value.filter { it.isDigit() || it == '-' || it.isLetter() }.take(20) })
        OnlineButtonRow {
            LemonButton(text = stringResource(R.string.online_send_request), primary = true) {
                if (code.isNotBlank()) {
                    actions.onAddFriend(code)
                    code = ""
                }
            }
        }
    }
}

/**
 * A member of the room. Tapping someone else (with a friend code) offers add as friend, block and
 * report; report asks once more before it is sent.
 */
@Composable
private fun MemberRow(member: OnlineRoom.Member, state: OnlineUiState, actions: OnlineActions) {
    var open by remember { mutableStateOf(false) }
    var confirmReport by remember { mutableStateOf(false) }
    val code = member.code
    val other = code != null && code != state.friendCode
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (other) Modifier.clickable { open = !open; confirmReport = false } else Modifier)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
        if (open && code != null) {
            if (!confirmReport) {
                OnlineButtonRow {
                    LemonButton(text = stringResource(R.string.online_report)) { confirmReport = true }
                    LemonButton(text = stringResource(R.string.online_block)) {
                        actions.onBlock(code)
                        open = false
                    }
                    if (state.friends.friends.none { it.code == code }) {
                        LemonButton(text = stringResource(R.string.online_add_as_friend), primary = true) {
                            actions.onAddFriend(code)
                            open = false
                        }
                    }
                }
            } else {
                BasicText(
                    stringResource(R.string.online_report_confirm, member.nickname),
                    style = LemonType.Body.copy(fontSize = 14.sp)
                )
                OnlineButtonRow {
                    LemonButton(text = stringResource(android.R.string.cancel)) { confirmReport = false }
                    LemonButton(text = stringResource(R.string.online_report_and_block)) {
                        actions.onReport(code, true)
                        open = false
                    }
                    LemonButton(text = stringResource(R.string.online_report), primary = true) {
                        actions.onReport(code, false)
                        open = false
                    }
                }
            }
        }
    }
}

/**
 * Public rooms anyone can join, refreshed every 15 s while shown. Tapping one opens it: the
 * password field when it has one, and Join.
 */
@Composable
private fun PublicLobbySection(state: OnlineUiState, actions: OnlineActions) {
    LaunchedEffect(Unit) {
        while (true) {
            actions.onRefreshLobby()
            delay(15_000)
        }
    }
    var open by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    Section(stringResource(R.string.online_public_rooms)) {
        if (state.lobby.isEmpty()) {
            BasicText(
                stringResource(R.string.online_no_public_rooms),
                style = LemonType.Body.copy(fontSize = 14.sp, color = LemonColors.TextMuted)
            )
        }
        for (room in state.lobby) {
            val expanded = open == room.roomId
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        open = if (expanded) null else room.roomId
                        password = ""
                    }
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            room.name.ifEmpty { stringResource(R.string.online_room_of, room.hostNickname) },
                            style = LemonType.Button,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        BasicText(
                            listOfNotNull(
                                room.titleName.ifEmpty { null },
                                if (room.hasPassword) stringResource(R.string.online_has_password) else null,
                                if (room.cheatsAllowed) stringResource(R.string.online_cheats_on_short) else null
                            ).joinToString(" · ").ifEmpty { stringResource(R.string.online_any_game) },
                            style = LemonType.Body.copy(fontSize = 13.sp, color = LemonColors.TextMuted),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicText(
                        if (room.full) {
                            stringResource(R.string.online_room_full_short)
                        } else {
                            "${room.members}/${room.maxMembers}"
                        },
                        style = LemonType.Button.copy(
                            color = if (room.full) LemonColors.TextMuted else LemonColors.Lemon
                        )
                    )
                }
                if (expanded && !room.full) {
                    if (room.hasPassword) {
                        FieldLabel(stringResource(R.string.online_password))
                        OnlineTextField(password, { password = it.take(64) })
                    }
                    OnlineButtonRow {
                        LemonButton(text = stringResource(R.string.online_join), primary = true) {
                            if (!state.busy) actions.onJoinPublic(room, password)
                        }
                    }
                }
            }
        }
    }
}

/** The longest start of [text] that fits in [max] bytes of UTF-8 (the service's limit for names). */
private fun utf8Prefix(text: String, max: Int): String {
    val out = StringBuilder()
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
        if (bytes + size > max) break
        out.appendCodePoint(cp)
        bytes += size
        i += Character.charCount(cp)
    }
    return out.toString()
}

/** Friends' invitations: accept goes straight into their room. */
@Composable
private fun InvitesSection(invites: List<OnlineInvite>, actions: OnlineActions) {
    val context = LocalContext.current
    Section(stringResource(R.string.online_invites)) {
        for (invite in invites) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(OnlineSession.inviteText(context, invite), style = LemonType.Button)
                OnlineButtonRow {
                    LemonButton(text = stringResource(R.string.online_decline)) {
                        actions.onDeclineInvite(invite)
                    }
                    LemonButton(text = stringResource(R.string.online_accept), primary = true) {
                        actions.onAcceptInvite(invite)
                    }
                }
            }
        }
    }
}

/** In a room: friends to invite, those playing or online first. */
@Composable
private fun InviteFriendsSection(state: OnlineUiState, actions: OnlineActions) {
    var invited by remember { mutableStateOf(setOf<String>()) }
    Section(stringResource(R.string.online_invite_friends)) {
        val friends = state.friends.friends.sortedWith(compareBy({ stateOrder(it.state) }, { it.nickname }))
        if (friends.isEmpty()) {
            BasicText(
                stringResource(R.string.online_no_friends_to_invite),
                style = LemonType.Body.copy(fontSize = 14.sp, color = LemonColors.TextMuted)
            )
        }
        for (friend in friends) {
            PersonRow(friend, presenceText(friend)) {
                OnlineButtonRow {
                    val done = friend.code in invited
                    LemonButton(
                        text = stringResource(if (done) R.string.online_invited else R.string.online_invite),
                        primary = !done
                    ) {
                        if (!done) {
                            invited = invited + friend.code
                            actions.onInvite(friend.code)
                        }
                    }
                }
            }
        }
    }
}

/** What friends see of us, and the people we blocked. */
@Composable
private fun PrivacySection(state: OnlineUiState, actions: OnlineActions) {
    Section(stringResource(R.string.online_privacy)) {
        OnlineSwitchRow(
            stringResource(R.string.online_appear_offline),
            stringResource(R.string.online_appear_offline_hint),
            state.privacy.appearOffline,
            actions.onAppearOffline
        )
        OnlineSwitchRow(
            stringResource(R.string.online_hide_game),
            stringResource(R.string.online_hide_game_hint),
            state.privacy.hideGame,
            actions.onHideGame
        )
        if (state.friends.blocked.isNotEmpty()) {
            FieldLabel(stringResource(R.string.online_blocked))
            for (person in state.friends.blocked) {
                PersonRow(person, OnlineIdentity.display(person.code)) {
                    OnlineButtonRow {
                        LemonButton(text = stringResource(R.string.online_unblock)) {
                            actions.onUnblock(person.code)
                        }
                    }
                }
            }
        }
    }
}

private fun stateOrder(state: String) = when (state) {
    "playing" -> 0
    "online" -> 1
    else -> 2
}

@Composable
private fun presenceText(friend: OnlineFriend): String {
    val base = when (friend.state) {
        "playing" -> if (friend.titleName.isNotEmpty()) {
            stringResource(R.string.online_playing_title, friend.titleName)
        } else {
            stringResource(R.string.online_playing)
        }
        "online" -> stringResource(R.string.online_state_online)
        else -> stringResource(R.string.online_state_offline)
    }
    return if (friend.roomId.isNotEmpty()) base + " · " + stringResource(R.string.online_in_a_room) else base
}

/** A person: presence dot, nickname, one line under it, and whatever [below] adds. */
@Composable
private fun PersonRow(
    person: OnlineFriend,
    detail: String,
    onClick: (() -> Unit)? = null,
    below: @Composable () -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(10.dp).clip(CircleShape).background(
                    when (person.state) {
                        "playing" -> LemonColors.Lemon
                        "online" -> LemonColors.Text
                        else -> LemonColors.TextMuted.copy(alpha = 0.5f)
                    }
                )
            )
            Column(Modifier.weight(1f)) {
                BasicText(
                    person.nickname.ifEmpty { OnlineIdentity.display(person.code) },
                    style = LemonType.Button,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                BasicText(
                    detail,
                    style = LemonType.Body.copy(fontSize = 13.sp, color = LemonColors.TextMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        below()
    }
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
        if (room.public) {
            BasicText(
                stringResource(R.string.online_room_is_public),
                style = LemonType.Body.copy(fontSize = 14.sp, color = LemonColors.TextMuted)
            )
        }
    }

    Section(stringResource(R.string.online_members, state.members.size, room.maxMembers)) {
        if (state.members.isEmpty()) {
            BasicText(stringResource(R.string.online_connecting), style = LemonType.Body)
        }
        for (member in state.members) {
            MemberRow(member, state, actions)
        }
    }

    InviteFriendsSection(state, actions)

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
            "bad_friend_code" -> stringResource(R.string.online_error_bad_friend_code)
            "own_friend_code" -> stringResource(R.string.online_error_own_friend_code)
            "user_not_found" -> stringResource(R.string.online_error_user_not_found)
            "blocked" -> stringResource(R.string.online_error_blocked)
            "friend_limit" -> stringResource(R.string.online_error_friend_limit)
            "not_friends" -> stringResource(R.string.online_error_not_friends)
            "invite_not_found" -> stringResource(R.string.online_error_invite_not_found)
            "room_closed" -> stringResource(R.string.online_error_room_closed)
            "identity_required" -> stringResource(R.string.online_error_unauthorized)
            "too_many_public_rooms" -> stringResource(R.string.online_error_too_many_public_rooms)
            "suspended" -> stringResource(R.string.online_error_suspended)
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
        is OnlineSession.Notice.Info -> when (notice.code) {
            "accepted", "already_friends" -> stringResource(R.string.online_friend_now_friends)
            "invited" -> stringResource(R.string.online_invite_sent)
            "reported" -> stringResource(R.string.online_reported)
            else -> stringResource(R.string.online_friend_request_sent)
        }
    }
    val good = notice is OnlineSession.Notice.Info
    val shape = RoundedCornerShape(18.dp)
    BasicText(
        text,
        style = LemonType.Button.copy(color = LemonColors.Text),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background((if (good) LemonColors.Lemon else LemonColors.Red).copy(alpha = 0.18f))
            .border(1.dp, (if (good) LemonColors.Lemon else LemonColors.Red).copy(alpha = 0.6f), shape)
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
