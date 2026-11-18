// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.online

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import dev.lemon.lemon_emu.LemonApplication
import dev.lemon.lemon_emu.utils.Log
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
        val cheatsAllowed: Boolean
    )

    /** What the screen shows while nothing is connected: null, or an error code to explain. */
    sealed interface Notice {
        data class Service(val code: String) : Notice
        data class Connection(val error: OnlineRoom.Error) : Notice
    }

    private const val PREFS = "lemon_online"
    private const val PREF_SERVER = "server_url"
    private const val PREF_NICKNAME = "nickname"
    private const val PREF_ENABLED = "enabled"
    private const val PREF_ATTEMPT = "identity_attempt"
    private const val PREF_CODE = "identity_code"
    private const val PREF_SECRET = "identity_secret"
    private const val PREF_REGISTERED_AT = "registered_server"

    private const val DEFAULT_SERVER = "https://online-staging.lemon-emu.org/v1"
    private const val MAX_NICKNAME_BYTES = 24
    private const val MAX_IDENTITY_ATTEMPTS = 5

    /** Empty until the policy is published; the consent screen links it when set. */
    const val PRIVACY_POLICY_URL = ""

    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice

    private val _enabled = MutableStateFlow(false)

    /** Lemon Online is off until the player accepts the consent screen. */
    val enabled: StateFlow<Boolean> = _enabled

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
        get() = LemonApplication.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(PREF_SERVER, null)?.ifBlank { null } ?: DEFAULT_SERVER
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
        if (started) return
        started = true
        _enabled.value = prefs.getBoolean(PREF_ENABLED, false) && storedIdentity() != null
        scope.launch {
            OnlineRoom.errors.collect { onConnectionError(it) }
        }
        // Cheats follow the room: off while in one that doesn't allow them, back when leaving it.
        scope.launch {
            _room.collect { room -> OnlineRoom.setCheatsBlocked(room != null && !room.cheatsAllowed) }
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

    fun create(
        maxMembers: Int,
        password: String?,
        cheatsAllowed: Boolean,
        titleId: String?,
        titleName: String?
    ) = request(null, password, cheatsAllowed) {
        it.createRoom(maxMembers, password, cheatsAllowed, titleId, titleName)
    }

    // Joining: a room that doesn't say whether it allows cheats doesn't allow them.
    fun join(code: String, password: String?) = request(code.trim().uppercase(), password, false) {
        it.joinRoom(code, password)
    }

    fun leave() {
        joinCode = null
        joinPassword = null
        _room.value = null
        thread(name = "OnlineLeave") { OnlineRoom.leave() }
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
                connect(ticket, cheatsIfUnsaid)
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

    private fun connect(ticket: RoomTicket, cheatsIfUnsaid: Boolean) {
        _room.value = Room(
            ticket.roomId,
            ticket.roomCode,
            ticket.maxMembers,
            ticket.titleName,
            ticket.cheatsAllowed ?: cheatsIfUnsaid
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
                    connect(authed { it.joinRoom(code, joinPassword) }, allowed)
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
