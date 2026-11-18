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
    val titleName: String
)

/** An error the service answered with ({"error":{"code":...}}), or "network" when it couldn't be reached. */
class OnlineException(val code: String, val httpStatus: Int, val retryAfterSeconds: Int? = null) :
    IOException("Lemon Online: $code ($httpStatus)")

/**
 * The Lemon Online service, docs/PROTOCOL.md in LemonNetwork. Blocking calls: use them off the UI
 * thread. Tickets are credentials: never log a response body.
 */
class OnlineApi(private val baseUrl: String) {
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Phase 1: anonymous room. Every field is optional on the service side. */
    fun createRoom(maxMembers: Int, password: String?, titleId: String?, titleName: String?): RoomTicket {
        val body = JSONObject().put("max_members", maxMembers)
        if (!password.isNullOrEmpty()) body.put("password", password)
        if (!titleId.isNullOrEmpty()) body.put("title_id", titleId)
        if (!titleName.isNullOrEmpty()) body.put("title_name", titleName)
        return post("/rooms", body).toTicket()
    }

    fun joinRoom(code: String, password: String?): RoomTicket {
        val body = JSONObject().put("room_code", code.trim().uppercase())
        if (!password.isNullOrEmpty()) body.put("password", password)
        return post("/rooms/join", body).toTicket()
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("X-Lemon-Client", "lemon/${BuildConfig.VERSION_NAME}")
            .post(body.toString().toRequestBody(json))
            .build()
        val response = try {
            client.newCall(request).execute()
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
            return JSONObject(text)
        }
    }

    private fun JSONObject.toTicket() = RoomTicket(
        roomId = getString("room_id"),
        roomCode = getString("room_code"),
        host = getString("host"),
        port = getInt("port"),
        ticket = getString("ticket"),
        maxMembers = optInt("max_members", 8),
        titleName = optString("title_name")
    )

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
