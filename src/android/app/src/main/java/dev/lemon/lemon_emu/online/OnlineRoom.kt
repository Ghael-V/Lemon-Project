// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.online

import androidx.annotation.Keep
import dev.lemon.lemon_emu.NativeLibrary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The core's room connection (jni/online_room.cpp): join a relay with a ticket, leave it, and
 * what the connection reports back. Native code calls the onNative* methods from its network
 * thread, so they only publish to flows. @Keep: R8 must not rename what JNI looks up by name.
 */
@Keep
object OnlineRoom {
    /** Same order as Network::RoomMember::State. */
    enum class State { Uninitialized, Idle, Joining, Joined, Moderator }

    /** Same order as Network::RoomMember::Error. */
    enum class Error {
        LostConnection, HostKicked, UnknownError, NameCollision, IpCollision, WrongVersion,
        WrongPassword, CouldNotConnect, RoomIsFull, HostBanned, PermissionDenied, NoSuchUser
    }

    data class Member(val nickname: String, val game: String)

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members

    private val _errors = MutableSharedFlow<Error>(extraBufferCapacity = 8)
    val errors: SharedFlow<Error> = _errors

    val isInRoom: Boolean
        get() = _state.value == State.Joined || _state.value == State.Moderator

    /** Hooks the native callbacks up once. Safe to call any number of times. */
    fun bind() {
        NativeLibrary // loads the native library if nothing has yet
        nativeBind()
        _state.value = State.entries.getOrElse(nativeState()) { State.Idle }
    }

    /** Blocks for up to a few seconds while ENet connects: never call it on the UI thread. */
    fun join(host: String, port: Int, nickname: String, ticket: String) =
        nativeJoin(host, port, nickname, ticket)

    fun leave() = nativeLeave()

    @JvmStatic
    private external fun nativeBind()

    @JvmStatic
    private external fun nativeJoin(host: String, port: Int, nickname: String, ticket: String)

    @JvmStatic
    private external fun nativeLeave()

    @JvmStatic
    private external fun nativeState(): Int

    @Keep
    @JvmStatic
    fun onNativeState(state: Int) {
        val value = State.entries.getOrElse(state) { State.Idle }
        _state.value = value
        if (value == State.Idle || value == State.Uninitialized) {
            _members.value = emptyList()
        }
    }

    @Keep
    @JvmStatic
    fun onNativeError(error: Int) {
        _errors.tryEmit(Error.entries.getOrElse(error) { Error.UnknownError })
    }

    @Keep
    @JvmStatic
    fun onNativeMembers(entries: Array<String>) {
        _members.value = entries.map {
            val parts = it.split('\u001f', limit = 2)
            Member(parts[0], parts.getOrElse(1) { "" })
        }
    }
}
