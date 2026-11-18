// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.online

import dev.ironlemon.ironlemon_emulator.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A room the service gave us: where its relay listens and the one-use ticket to get in. */
data class RoomTicket(
    val roomId: String,
    val roomCode: String,
    val host: String,
    val port: Int,
    val ticket: String,
    val maxMembers: Int,
    val titleName: String,
    /** Whether the room lets its players use cheats; null when the service didn't say. */
    val cheatsAllowed: Boolean?
)

/** Another player as the friends list shows them. [state] is offline, online or playing. */
data class OnlineFriend(
    val code: String,
    val nickname: String,
    val state: String = "offline",
    val titleName: String = "",
    val roomId: String = ""
)

/** Everything GET /friends answers: friends with their presence, requests both ways, blocks. */
data class FriendLists(
    val friends: List<OnlineFriend> = emptyList(),
    val incoming: List<OnlineFriend> = emptyList(),
    val outgoing: List<OnlineFriend> = emptyList(),
    val blocked: List<OnlineFriend> = emptyList()
)

/** An error the service answered with ({"error":{"code":...}}), or "network" when it couldn't be reached. */
class OnlineException(val code: String, val httpStatus: Int, val retryAfterSeconds: Int? = null) :
    IOException("Lemon Online: $code ($httpStatus)")

/**
 * The Lemon Online service, docs/PROTOCOL.md in LemonNetwork. Blocking calls: use them off the UI
 * thread. With [auth] every request carries the identity headers. Tickets and secrets are
 * credentials: never log a request or a response body.
 */
class OnlineApi(private val baseUrl: String, private val auth: OnlineIdentity.Identity? = null) {
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Tells the service about this identity. Same code and secret again is fine; 409 code_taken when not. */
    fun register(identity: OnlineIdentity.Identity, nickname: String) {
        val body = JSONObject()
            .put("code", identity.code)
            .put("secret", identity.secret)
            .put("nickname", nickname)
            .put("client", CLIENT)
            .put("app_version", BuildConfig.VERSION_NAME)
        send("POST", "/register", body)
    }

    fun updateNickname(nickname: String) {
        send("PATCH", "/me", JSONObject().put("nickname", nickname))
    }

    /** The privacy switches as the service has them: appear_offline and hide_game. */
    fun getMe(): JSONObject = send("GET", "/me", null)

    fun setPrivacy(appearOffline: Boolean? = null, hideGame: Boolean? = null) {
        val body = JSONObject()
        appearOffline?.let { body.put("appear_offline", it) }
        hideGame?.let { body.put("hide_game", it) }
        send("PATCH", "/me", body)
    }

    fun friends(): FriendLists {
        val json = send("GET", "/friends", null)
        fun list(name: String): List<OnlineFriend> {
            val array = json.optJSONArray(name) ?: return emptyList()
            return (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val presence = item.optJSONObject("presence")
                OnlineFriend(
                    code = item.getString("code"),
                    nickname = item.optString("nickname"),
                    state = presence?.optString("state")?.ifEmpty { null } ?: "offline",
                    titleName = presence?.optString("title_name").orEmpty(),
                    roomId = presence?.optString("room_id").orEmpty()
                )
            }
        }
        return FriendLists(list("friends"), list("incoming"), list("outgoing"), list("blocked"))
    }

    /** Answers "sent", "accepted" (they had asked us already) or "already_friends". */
    fun requestFriend(code: String): String =
        send("POST", "/friends/requests", JSONObject().put("code", code)).optString("status")

    fun answerFriendRequest(code: String, accept: Boolean) {
        send("POST", "/friends/requests/$code/${if (accept) "accept" else "decline"}", JSONObject())
    }

    /** Ends a friendship, or withdraws a request we sent. */
    fun removeFriend(code: String) {
        send("DELETE", "/friends/$code", null)
    }

    fun block(code: String) {
        send("PUT", "/blocks/$code", JSONObject())
    }

    fun unblock(code: String) {
        send("DELETE", "/blocks/$code", null)
    }

    /** Erases the identity, its friends and blocks, and closes the rooms it created. */
    fun deleteMe() {
        send("DELETE", "/me", null)
    }

    fun createRoom(
        maxMembers: Int,
        password: String?,
        cheatsAllowed: Boolean,
        titleId: String?,
        titleName: String?
    ): RoomTicket {
        val body = JSONObject().put("max_members", maxMembers).put("cheats_allowed", cheatsAllowed)
        if (!password.isNullOrEmpty()) body.put("password", password)
        if (!titleId.isNullOrEmpty()) body.put("title_id", titleId)
        if (!titleName.isNullOrEmpty()) body.put("title_name", titleName)
        return send("POST", "/rooms", body).toTicket()
    }

    fun joinRoom(code: String, password: String?): RoomTicket {
        val body = JSONObject().put("room_code", code.trim().uppercase())
        if (!password.isNullOrEmpty()) body.put("password", password)
        return send("POST", "/rooms/join", body).toTicket()
    }

    private fun send(method: String, path: String, body: JSONObject?): JSONObject {
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("X-Lemon-Client", "$CLIENT/${BuildConfig.VERSION_NAME}")
            .method(method, body?.toString()?.toRequestBody(json))
        auth?.let {
            builder.header("X-Lemon-Code", it.code)
            builder.header("X-Lemon-Secret", it.secret)
        }
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw OnlineException("network", 0)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val code = runCatching {
                    JSONObject(text).getJSONObject("error").getString("code")
                }.getOrDefault("http_${it.code}")
                throw OnlineException(code, it.code, it.header("Retry-After")?.toIntOrNull())
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun JSONObject.toTicket() = RoomTicket(
        roomId = getString("room_id"),
        roomCode = getString("room_code"),
        host = getString("host"),
        port = getInt("port"),
        ticket = getString("ticket"),
        maxMembers = optInt("max_members", 8),
        titleName = optString("title_name"),
        cheatsAllowed = if (has("cheats_allowed")) optBoolean("cheats_allowed") else null
    )

    companion object {
        private const val CLIENT = "ironlemon"

        private val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
