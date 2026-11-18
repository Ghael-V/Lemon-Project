// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.online

import dev.lemon.lemon_emu.BuildConfig
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
        private const val CLIENT = "lemon"

        private val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
