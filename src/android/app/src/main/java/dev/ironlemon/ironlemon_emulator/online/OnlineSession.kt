// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.online

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.edit
import dev.ironlemon.ironlemon_emulator.BuildConfig
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.YuzuApplication
import dev.ironlemon.ironlemon_emulator.utils.Log
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ironlemon.ironlemon_emulator.online.OnlineApi.Companion.toInvite
import org.json.JSONObject

/**
 * Lemon Online: an identity with no account (see [OnlineIdentity]) and rooms. Create a room or
 * join one by its code through the service, then connect the core to the room's relay with the
 * ticket. One room at a time; staying in it across games is fine (the core tells the room which
 * game is running). Nothing reaches the service before the player turns Lemon Online on.
 */
object OnlineSession {
    /** The room we are in or getting into. Null when not in a room. */
    data class Room(
        val id: String,
        val code: String,
        val maxMembers: Int,
        val titleName: String,
        val cheatsAllowed: Boolean,
        val public: Boolean = false,
        /** We created it: leaving closes it. */
        val own: Boolean = false
    )

    /** What the screen shows while nothing is connected: null, or an error code to explain. */
    sealed interface Notice {
        data class Service(val code: String) : Notice
        data class Connection(val error: OnlineRoom.Error) : Notice

        /** Good news, shown without the error colour: "sent", "accepted", "already_friends". */
        data class Info(val code: String, val detail: String = "") : Notice
    }

    /** The two privacy switches; the service applies them to what friends see. */
    data class Privacy(val appearOffline: Boolean = false, val hideGame: Boolean = false)

    private const val PREFS = "lemon_online"
    private const val PREF_SERVER = "server_url"
    private const val PREF_NICKNAME = "nickname"
    private const val PREF_ENABLED = "enabled"
    private const val PREF_ATTEMPT = "identity_attempt"
    private const val PREF_CODE = "identity_code"
    private const val PREF_SECRET = "identity_secret"
    private const val PREF_REGISTERED_AT = "registered_server"
    private const val PREF_APPEAR_OFFLINE = "appear_offline"
    private const val PREF_HIDE_GAME = "hide_game"
    private const val PREF_OWN_PUBLIC_ROOM = "own_public_room"

    /** The live service (its name says staging, but it is the one players use). */
    private const val DEFAULT_SERVER = "https://online-staging.lemon-emu.org/v1"
    private const val MAX_NICKNAME_BYTES = 24
    private const val MAX_IDENTITY_ATTEMPTS = 5

    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice

    private val _enabled = MutableStateFlow(false)

    /** Lemon Online is off until the player accepts the consent screen. */
    val enabled: StateFlow<Boolean> = _enabled

    private val _friends = MutableStateFlow(FriendLists())

    /** Friends with their presence, requests both ways and blocks; refreshed by the screen and by events. */
    val friends: StateFlow<FriendLists> = _friends

    private val _privacy = MutableStateFlow(Privacy())
    val privacy: StateFlow<Privacy> = _privacy

    private val _lobby = MutableStateFlow<List<PublicRoom>>(emptyList())

    /** The public rooms the lobby shows; refreshed while the screen is open. */
    val lobby: StateFlow<List<PublicRoom>> = _lobby

    private val _invites = MutableStateFlow<List<OnlineInvite>>(emptyList())

    /** Friends' invitations to their rooms, newest last. */
    val invites: StateFlow<List<OnlineInvite>> = _invites

    // The presence socket while Lemon Online is on, and the game being played (for presence).
    private var presence: OnlinePresence? = null
    private var playingTitleId: String? = null
    private var playingTitleName: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** In a room that doesn't allow cheats: the game's cheat menus stay closed. */
    val cheatsBlocked: Boolean
        get() = _room.value?.let { !it.cheatsAllowed } ?: false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nicknameChanges = MutableStateFlow<String?>(null)

    // How we got in, kept only in memory so a refused ticket can be replaced once.
    private var joinCode: String? = null
    private var joinPassword: String? = null
    private var retried = false
    private var started = false

    private val prefs
        get() = YuzuApplication.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The service. Only debug builds can point elsewhere (an address typed while testing is ignored). */
    var serverUrl: String
        get() = if (BuildConfig.DEBUG) {
            prefs.getString(PREF_SERVER, null)?.ifBlank { null } ?: DEFAULT_SERVER
        } else {
            DEFAULT_SERVER
        }
        set(value) = prefs.edit { putString(PREF_SERVER, value.trim()) }

    var nickname: String
        get() = prefs.getString(PREF_NICKNAME, null) ?: Build.MODEL.orEmpty().ifBlank { "Player" }
        set(value) {
            prefs.edit { putString(PREF_NICKNAME, value) }
            nicknameChanges.value = value
        }

    /** The 12-digit friend code once Lemon Online is on. */
    val friendCode: String?
        get() = if (_enabled.value) prefs.getString(PREF_CODE, null) else null

    /** Binds the native side and starts following its state. Idempotent. */
    @OptIn(FlowPreview::class)
    fun start() {
        OnlineRoom.bind()
        if (started) {
            presence?.resume()
            return
        }
        started = true
        _enabled.value = prefs.getBoolean(PREF_ENABLED, false) && storedIdentity() != null
        _privacy.value = Privacy(
            prefs.getBoolean(PREF_APPEAR_OFFLINE, false),
            prefs.getBoolean(PREF_HIDE_GAME, false)
        )
        if (_enabled.value) openPresence()
        scope.launch {
            OnlineRoom.errors.collect { onConnectionError(it) }
        }
        // Cheats follow the room: off while in one that doesn't allow them, back when leaving it.
        scope.launch {
            _room.collect { room ->
                OnlineRoom.setCheatsBlocked(room != null && !room.cheatsAllowed)
                sendPresence()
            }
        }
        scope.launch {
            OnlineRoom.state.collect { state ->
                if (state == OnlineRoom.State.Idle && _room.value != null && !_busy.value) {
                    _room.value = null
                }
            }
        }
        // The name is typed letter by letter: tell the service once the typing stops.
        scope.launch {
            nicknameChanges.filterNotNull().debounce(1500).collect { name ->
                if (!_enabled.value) return@collect
                withContext(Dispatchers.IO) {
                    try {
                        authed { it.updateNickname(safeNickname(name)) }
                    } catch (e: OnlineException) {
                        Log.warning("[Online] nickname not saved: ${e.code} (${e.httpStatus})")
                    }
                }
            }
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** The consent screen's button: work out the identity and register it with the service. */
    fun enable(name: String) {
        if (_busy.value) return
        prefs.edit { putString(PREF_NICKNAME, name) }
        _busy.value = true
        _notice.value = null
        thread(name = "OnlineEnable") {
            try {
                register()
                prefs.edit { putBoolean(PREF_ENABLED, true) }
                _enabled.value = true
                openPresence()
                refreshFriends()
                Log.info("[Online] Lemon Online turned on")
            } catch (e: OnlineException) {
                Log.warning("[Online] turning on failed: ${e.code} (${e.httpStatus})")
                _notice.value = Notice.Service(e.code)
            } catch (e: Exception) {
                Log.warning("[Online] turning on failed: ${e.javaClass.simpleName}")
                _notice.value = Notice.Service("identity")
            } finally {
                _busy.value = false
            }
        }
    }

    /** "Erase my data": the service forgets this identity and Lemon Online goes back off. */
    fun deleteData() {
        if (_busy.value) return
        _busy.value = true
        _notice.value = null
        thread(name = "OnlineDelete") {
            try {
                storedIdentity()?.let { OnlineApi(serverUrl, it).deleteMe() }
                forget()
            } catch (e: OnlineException) {
                // Unknown to this server already: nothing left to erase there.
                if (e.httpStatus == 401) {
                    forget()
                } else {
                    Log.warning("[Online] erasing failed: ${e.code} (${e.httpStatus})")
                    _notice.value = Notice.Service(e.code)
                }
            } finally {
                _busy.value = false
            }
        }
    }

    private fun forget() {
        leave()
        presence?.close()
        presence = null
        _friends.value = FriendLists()
        // The attempt number stays: the codes before it belong to other devices.
        prefs.edit {
            remove(PREF_ENABLED)
            remove(PREF_CODE)
            remove(PREF_SECRET)
            remove(PREF_REGISTERED_AT)
        }
        _enabled.value = false
        Log.info("[Online] Lemon Online data erased")
    }

    /** Called when a game starts (with its title) and when it stops (nulls). */
    fun setPlaying(titleId: String?, titleName: String?) {
        playingTitleId = titleId
        playingTitleName = titleName
        // The app with a game running is the one friends should see.
        if (titleId != null) presence?.resume()
        sendPresence()
    }

    private fun sendPresence() {
        val socket = presence ?: return
        val playing = playingTitleId != null
        socket.setPresence(
            JSONObject()
                .put("t", "presence")
                .put("state", if (playing) "playing" else "online")
                .put("title_id", playingTitleId.orEmpty())
                .put("title_name", playingTitleName.orEmpty())
                .put("room_id", _room.value?.id ?: JSONObject.NULL)
        )
    }

    private fun openPresence() {
        if (presence != null) return
        val identity = storedIdentity() ?: return
        presence = OnlinePresence(serverUrl, identity, ::onPresenceEvent) {
            // A server that lost us: register again on a worker thread, then reopen.
            thread(name = "OnlineReRegister") {
                try {
                    presence = null
                    register()
                    openPresence()
                } catch (e: OnlineException) {
                    Log.warning("[Online] presence refused: ${e.code} (${e.httpStatus})")
                }
            }
        }.also {
            it.open()
        }
        sendPresence()
    }

    private fun onPresenceEvent(event: JSONObject) {
        when (event.optString("t")) {
            "presence" -> {
                val code = event.optString("code")
                val lists = _friends.value
                if (lists.friends.none { it.code == code }) {
                    refreshFriends()
                    return
                }
                _friends.value = lists.copy(friends = lists.friends.map {
                    if (it.code != code) it else it.copy(
                        nickname = event.optString("nickname").ifEmpty { it.nickname },
                        state = event.optString("state", "offline"),
                        titleName = event.optString("title_name"),
                        roomId = event.optString("room_id")
                    )
                })
            }
            "friend_request" -> {
                toast(R.string.online_friend_request_from, event.optString("nickname"))
                refreshFriends()
            }
            "friend_accepted" -> {
                toast(R.string.online_friend_accepted_by, event.optString("nickname"))
                refreshFriends()
            }
            "invite" -> {
                val invite = runCatching { event.toInvite() }.getOrNull() ?: return
                // The service keeps one invite per friend and room: a new one replaces the old.
                _invites.value = _invites.value.filterNot {
                    it.id == invite.id || (it.fromCode == invite.fromCode && it.roomId == invite.roomId)
                } + invite
                mainHandler.post {
                    val context = YuzuApplication.appContext
                    Toast.makeText(context, inviteText(context, invite), Toast.LENGTH_LONG).show()
                }
            }
            "room_closed" -> {
                val roomId = event.optString("room_id")
                _invites.value = _invites.value.filterNot { it.roomId == roomId }
            }
        }
    }

    /** "Ana invites you to play Mario Kart 8 Deluxe", or to a room when the game isn't known. */
    fun inviteText(context: Context, invite: OnlineInvite): String = if (invite.titleName.isNotEmpty()) {
        context.getString(R.string.online_invite_from, invite.fromNickname, invite.titleName)
    } else {
        context.getString(R.string.online_invite_from_room, invite.fromNickname)
    }

    fun inviteFriend(code: String) {
        val roomId = _room.value?.id ?: return
        friendAction { api ->
            api.invite(code, roomId)
            _notice.value = Notice.Info("invited")
        }
    }

    /** The program id (hex) of the game running now, if any. */
    val playingTitle: String?
        get() = playingTitleId

    /** Shows a piece of good news, such as which game to open now. */
    fun inform(code: String, detail: String = "") {
        _notice.value = Notice.Info(code, detail)
    }

    /**
     * Leaves the room we are in, if any, and goes into the friend's. [onJoined] runs on the main
     * thread once the service let us in (the relay connection is still being made).
     */
    fun acceptInvite(invite: OnlineInvite, onJoined: (() -> Unit)? = null) {
        _invites.value = _invites.value.filterNot { it.id == invite.id }
        val left = _room.value
        request(null, null, false, then = onJoined) { api ->
            val ticket = api.acceptInvite(invite.id)
            if (left != null) {
                OnlineRoom.leave()
                if (left.own) runCatching { api.closeRoom(left.id) }
            }
            ticket
        }
    }

    fun declineInvite(invite: OnlineInvite) {
        _invites.value = _invites.value.filterNot { it.id == invite.id }
        thread(name = "OnlineDecline") {
            try {
                authed { it.declineInvite(invite.id) }
            } catch (e: OnlineException) {
                Log.warning("[Online] decline failed: ${e.code} (${e.httpStatus})")
            }
        }
    }

    private fun toast(resId: Int, name: String) {
        mainHandler.post {
            val context = YuzuApplication.appContext
            Toast.makeText(context, context.getString(resId, name), Toast.LENGTH_LONG).show()
        }
    }

    /** Reloads friends, requests, blocks and the privacy switches from the service. */
    fun refreshFriends() {
        if (!_enabled.value) return
        presence?.resume() ?: openPresence()
        thread(name = "OnlineFriends") {
            try {
                _friends.value = authed { it.friends() }
                _invites.value = authed { it.invites() }
                val me = authed { it.getMe() }
                updatePrivacy(Privacy(me.optBoolean("appear_offline"), me.optBoolean("hide_game")))
            } catch (e: OnlineException) {
                Log.warning("[Online] friends not loaded: ${e.code} (${e.httpStatus})")
            }
        }
    }

    /** Sends a friend request to a code typed as LM-1234-5678-9012 or as its 12 digits. */
    fun addFriend(input: String) {
        val code = input.filter { it.isDigit() }
        when {
            code.length != 12 -> _notice.value = Notice.Service("bad_friend_code")
            code == prefs.getString(PREF_CODE, null) -> _notice.value = Notice.Service("own_friend_code")
            else -> friendAction { api -> _notice.value = Notice.Info(api.requestFriend(code)) }
        }
    }

    fun answerFriendRequest(code: String, accept: Boolean) =
        friendAction { it.answerFriendRequest(code, accept) }

    fun removeFriend(code: String) = friendAction { it.removeFriend(code) }

    fun block(code: String) = friendAction { it.block(code) }

    fun unblock(code: String) = friendAction { it.unblock(code) }

    fun setAppearOffline(value: Boolean) = changePrivacy(_privacy.value.copy(appearOffline = value))

    fun setHideGame(value: Boolean) = changePrivacy(_privacy.value.copy(hideGame = value))

    private fun changePrivacy(wanted: Privacy) {
        val before = _privacy.value
        updatePrivacy(wanted)
        thread(name = "OnlinePrivacy") {
            try {
                authed { it.setPrivacy(wanted.appearOffline, wanted.hideGame) }
            } catch (e: OnlineException) {
                updatePrivacy(before)
                _notice.value = Notice.Service(e.code)
            }
        }
    }

    private fun updatePrivacy(value: Privacy) {
        _privacy.value = value
        prefs.edit {
            putBoolean(PREF_APPEAR_OFFLINE, value.appearOffline)
            putBoolean(PREF_HIDE_GAME, value.hideGame)
        }
    }

    private fun friendAction(action: (OnlineApi) -> Unit) {
        if (!_enabled.value) return
        _notice.value = null
        thread(name = "OnlineFriendAction") {
            try {
                authed(action)
                _friends.value = authed { it.friends() }
            } catch (e: OnlineException) {
                Log.warning("[Online] friend action failed: ${e.code} (${e.httpStatus})")
                _notice.value = Notice.Service(e.code)
            }
        }
    }

    fun create(
        maxMembers: Int,
        password: String?,
        cheatsAllowed: Boolean,
        titleId: String?,
        titleName: String?,
        public: Boolean = false,
        name: String? = null
    ) = request(null, password, cheatsAllowed, own = true) { api ->
        // Created from inside a game (in-game menu): the room is for that game.
        fun createRoom() = api.createRoom(
            maxMembers,
            password,
            cheatsAllowed,
            titleId ?: playingTitleId,
            titleName ?: playingTitleName,
            public,
            name
        )
        val ticket = try {
            createRoom()
        } catch (e: OnlineException) {
            // Our last public room outlived the app (it closed without leaving, e.g. reinstalled):
            // the service still counts it, so close it and try again instead of making the user wait.
            val stale = prefs.getString(PREF_OWN_PUBLIC_ROOM, null)
            if (e.code != "too_many_public_rooms" || stale == null) throw e
            closeStalePublicRoom(api, stale)
            createAfterClose(::createRoom)
        }
        if (ticket.public) prefs.edit { putString(PREF_OWN_PUBLIC_ROOM, ticket.roomId) }
        ticket
    }

    private fun closeStalePublicRoom(api: OnlineApi, roomId: String) {
        Log.info("[Online] closing our previous public room")
        val gone = try {
            api.closeRoom(roomId)
            true
        } catch (e: OnlineException) {
            Log.warning("[Online] previous public room not closed: ${e.code} (${e.httpStatus})")
            e.code == "room_not_found"
        }
        if (gone) prefs.edit { remove(PREF_OWN_PUBLIC_ROOM) }
    }

    /**
     * The service stops the old room's relay and only stops counting it once the relay has exited,
     * a moment later: try a few times before giving up with its answer.
     */
    private fun createAfterClose(create: () -> RoomTicket): RoomTicket {
        var last: OnlineException? = null
        repeat(6) {
            Thread.sleep(500)
            try {
                return create()
            } catch (e: OnlineException) {
                if (e.code != "too_many_public_rooms") throw e
                last = e
            }
        }
        throw last!!
    }

    /** Loads the lobby; quietly keeps the old list when the service can't be reached. */
    fun refreshLobby() {
        if (!_enabled.value) return
        thread(name = "OnlineLobby") {
            try {
                // An empty room is one whose players all left: the service keeps it ~10 min before
                // closing it, and nobody is there to play with.
                _lobby.value = authed { it.publicRooms(playingTitleId) }.filter { it.members > 0 }
            } catch (e: OnlineException) {
                Log.warning("[Online] lobby not loaded: ${e.code} (${e.httpStatus})")
            }
        }
    }

    // A public room says whether it allows cheats; the join reply says it again.
    fun joinPublic(room: PublicRoom, password: String?) =
        request(null, password, room.cheatsAllowed) { it.joinPublicRoom(room.roomId, password) }

    /** Reports a member of our room; also blocks them when [block] is set. */
    /** Reports the host of a public room from the lobby; also blocks them when [block] is set. */
    fun reportRoom(roomId: String, block: Boolean) = friendAction { api ->
        api.reportRoom(roomId)
        if (block) api.blockRoom(roomId)
        _notice.value = Notice.Info("reported")
        if (block) refreshLobby()
    }

    /** Blocks the host of a public room from the lobby: their rooms disappear from it. */
    fun blockRoom(roomId: String) = friendAction { api ->
        api.blockRoom(roomId)
        refreshLobby()
    }

    fun unblockRef(ref: String) = friendAction { it.unblockRef(ref) }

    fun report(code: String, block: Boolean) {
        val roomId = _room.value?.id
        friendAction { api ->
            api.report(code, roomId)
            if (block) api.block(code)
            _notice.value = Notice.Info("reported")
        }
    }

    // Joining: a room that doesn't say whether it allows cheats doesn't allow them.
    fun join(code: String, password: String?) = request(code.trim().uppercase(), password, false) {
        it.joinRoom(code, password)
    }

    fun leave() {
        val left = _room.value
        joinCode = null
        joinPassword = null
        _room.value = null
        thread(name = "OnlineLeave") {
            OnlineRoom.leave()
            // Our own room closes now, not ~10 min later, and stops counting as our public room.
            if (left?.own == true) closeRoom(left.id)
        }
    }

    private fun closeRoom(roomId: String) {
        try {
            authed { it.closeRoom(roomId) }
            if (prefs.getString(PREF_OWN_PUBLIC_ROOM, null) == roomId) {
                prefs.edit { remove(PREF_OWN_PUBLIC_ROOM) }
            }
        } catch (e: OnlineException) {
            Log.warning("[Online] room not closed: ${e.code} (${e.httpStatus})")
        } catch (e: Exception) {
            Log.warning("[Online] room not closed: ${e.javaClass.simpleName}")
        }
    }

    private fun storedIdentity(): OnlineIdentity.Identity? {
        val code = prefs.getString(PREF_CODE, null) ?: return null
        val secret = prefs.getString(PREF_SECRET, null) ?: return null
        return OnlineIdentity.Identity(code, secret)
    }

    /**
     * Registers the identity with the current server, working it out first when there is none
     * yet. A code another device already has moves on to the next attempt, as the protocol says.
     */
    private fun register(): OnlineIdentity.Identity {
        val androidId = OnlineIdentity.androidId() ?: throw OnlineException("identity", 0)
        var attempt = prefs.getInt(PREF_ATTEMPT, 1)
        var identity = storedIdentity()
        repeat(MAX_IDENTITY_ATTEMPTS) {
            val current = identity ?: OnlineIdentity.derive(androidId, attempt).also { derived ->
                prefs.edit {
                    putInt(PREF_ATTEMPT, attempt)
                    putString(PREF_CODE, derived.code)
                    putString(PREF_SECRET, derived.secret)
                    remove(PREF_REGISTERED_AT)
                }
            }
            try {
                OnlineApi(serverUrl).register(current, safeNickname(nickname))
                prefs.edit { putString(PREF_REGISTERED_AT, serverUrl) }
                return current
            } catch (e: OnlineException) {
                if (e.code != "code_taken") throw e
                Log.info("[Online] friend code taken, trying the next one")
                attempt++
                identity = null
            }
        }
        throw OnlineException("code_taken", 409)
    }

    /**
     * Runs [call] with the identity headers. The first time on a server, and once more when the
     * server doesn't know us (a new database), it registers the identity first.
     */
    private fun <T> authed(call: (OnlineApi) -> T): T {
        var identity = storedIdentity()
        if (identity == null || prefs.getString(PREF_REGISTERED_AT, null) != serverUrl) {
            identity = register()
        }
        return try {
            call(OnlineApi(serverUrl, identity))
        } catch (e: OnlineException) {
            if (e.httpStatus != 401) throw e
            Log.info("[Online] the service didn't know this identity, registering again")
            call(OnlineApi(serverUrl, register()))
        }
    }

    private fun request(
        code: String?,
        password: String?,
        cheatsIfUnsaid: Boolean,
        own: Boolean = false,
        then: (() -> Unit)? = null,
        call: (OnlineApi) -> RoomTicket
    ) {
        if (_busy.value || !_enabled.value) return
        _busy.value = true
        _notice.value = null
        retried = false
        thread(name = "OnlineJoin") {
            try {
                val ticket = authed(call)
                joinCode = ticket.roomCode
                joinPassword = password
                connect(ticket, cheatsIfUnsaid, own)
                then?.let { Handler(Looper.getMainLooper()).post(it) }
            } catch (e: OnlineException) {
                Log.warning("[Online] service answered ${e.code} (${e.httpStatus})")
                _notice.value = Notice.Service(e.code)
            } catch (e: Exception) {
                Log.warning("[Online] request failed: ${e.javaClass.simpleName}")
                _notice.value = Notice.Service("network")
            } finally {
                _busy.value = false
            }
        }
    }

    private fun connect(ticket: RoomTicket, cheatsIfUnsaid: Boolean, own: Boolean) {
        _room.value = Room(
            ticket.roomId,
            ticket.roomCode,
            ticket.maxMembers,
            ticket.titleName,
            ticket.cheatsAllowed ?: cheatsIfUnsaid,
            ticket.public,
            own
        )
        OnlineRoom.join(ticket.host, ticket.port, safeNickname(nickname), ticket.ticket)
    }

    private fun onConnectionError(error: OnlineRoom.Error) {
        val code = joinCode
        // A refused ticket (expired, or the relay restarted): ask for a new one once.
        if (error == OnlineRoom.Error.WrongPassword && code != null && !retried) {
            retried = true
            Log.info("[Online] ticket refused, asking for a new one")
            thread(name = "OnlineRetry") {
                try {
                    val allowed = _room.value?.cheatsAllowed ?: false
                    val own = _room.value?.own ?: false
                    val roomId = _room.value?.id
                    // A fresh ticket for the same room; asking by code again needs the password.
                    val ticket = authed { api ->
                        roomId?.let { runCatching { api.renewTicket(it) }.getOrNull() }
                            ?: api.joinRoom(code, joinPassword)
                    }
                    connect(ticket, allowed, own)
                } catch (e: OnlineException) {
                    _room.value = null
                    _notice.value = Notice.Service(e.code)
                }
            }
            return
        }
        _room.value = null
        joinCode = null
        joinPassword = null
        _notice.value = Notice.Connection(error)
    }

    /** 1-24 bytes of UTF-8, cut on a character boundary, no control characters (the relay's rule). */
    fun safeNickname(name: String): String {
        val clean = name.filter { !it.isISOControl() }.trim().ifBlank { "Player" }
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < clean.length) {
            val cp = clean.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > MAX_NICKNAME_BYTES) break
            out.appendCodePoint(cp)
            bytes += size
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
