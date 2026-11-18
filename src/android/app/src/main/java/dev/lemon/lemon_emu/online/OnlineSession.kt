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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Lemon Online rooms, phase 1: create a room or join one by its code through the service, then
 * connect the core to the room's relay with the ticket. One room at a time; staying in it across
 * games is fine (the core tells the room which game is running).
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

    // Until the service has its own address (TLS on the VPS) it runs on the developer's PC.
    private const val DEFAULT_SERVER = "http://192.168.1.17:8080/v1"
    private const val MAX_NICKNAME_BYTES = 24

    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice

    /** In a room that doesn't allow cheats: the game's cheat menus stay closed. */
    val cheatsBlocked: Boolean
        get() = _room.value?.let { !it.cheatsAllowed } ?: false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // How we got in, kept only in memory so a refused ticket can be replaced once.
    private var joinCode: String? = null
    private var joinPassword: String? = null
    private var retried = false
    private var started = false

    private val prefs
        get() = LemonApplication.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(PREF_SERVER, null) ?: DEFAULT_SERVER
        set(value) = prefs.edit { putString(PREF_SERVER, value.trim()) }

    var nickname: String
        get() = prefs.getString(PREF_NICKNAME, null) ?: Build.MODEL.orEmpty().ifBlank { "Player" }
        set(value) = prefs.edit { putString(PREF_NICKNAME, value) }

    /** Binds the native side and starts following its state. Idempotent. */
    fun start() {
        OnlineRoom.bind()
        if (started) return
        started = true
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
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun create(
        maxMembers: Int,
        password: String?,
        cheatsAllowed: Boolean,
        titleId: String?,
        titleName: String?
    ) = request(null, password, cheatsAllowed) {
        OnlineApi(serverUrl).createRoom(maxMembers, password, cheatsAllowed, titleId, titleName)
    }

    // Joining: a room that doesn't say whether it allows cheats doesn't allow them.
    fun join(code: String, password: String?) = request(code.trim().uppercase(), password, false) {
        OnlineApi(serverUrl).joinRoom(code, password)
    }

    fun leave() {
        joinCode = null
        joinPassword = null
        _room.value = null
        thread(name = "OnlineLeave") { OnlineRoom.leave() }
    }

    private fun request(
        code: String?,
        password: String?,
        cheatsIfUnsaid: Boolean,
        call: () -> RoomTicket
    ) {
        if (_busy.value) return
        _busy.value = true
        _notice.value = null
        retried = false
        thread(name = "OnlineJoin") {
            try {
                val ticket = call()
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
                    connect(OnlineApi(serverUrl).joinRoom(code, joinPassword), allowed)
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
