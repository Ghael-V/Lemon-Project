// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.online

import dev.lemon.lemon_emu.BuildConfig
import dev.lemon.lemon_emu.utils.Log
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * The presence socket of Lemon Online (docs/PROTOCOL.md section 4): tells friends whether we are
 * online or playing, and hears about theirs, friend requests and closed rooms. One connection,
 * reopened with a growing delay when it drops. The service keeps one socket per identity, and
 * Lemon and Iron Lemon signed with the same key share the identity on a device: when another app
 * or device takes the presence over, this one waits for [resume] instead of fighting back.
 * The identity headers go on the upgrade request: never log them.
 */
class OnlinePresence(
    private val baseUrl: String,
    private val identity: OnlineIdentity.Identity,
    private val onEvent: (JSONObject) -> Unit,
    private val onUnauthorized: () -> Unit
) {
    private var socket: WebSocket? = null
    private var closed = false
    private var attempt = 0
    private var lastPresence: JSONObject? = null
    private val lock = Any()

    fun open() {
        synchronized(lock) {
            closed = false
            connect()
        }
    }

    /** Takes the presence back after another app or device took it, or reconnects if it dropped. */
    fun resume() {
        synchronized(lock) {
            if (closed || socket != null) return
            attempt = 0
            connect()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            socket?.close(1000, null)
            socket = null
        }
    }

    /** Sends our presence now and again after every reconnection. */
    fun setPresence(presence: JSONObject) {
        synchronized(lock) {
            lastPresence = presence
            socket?.send(presence.toString())
        }
    }

    private fun connect() {
        val url = baseUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws"
        val request = Request.Builder()
            .url(url)
            .header("X-Lemon-Client", "$CLIENT/${BuildConfig.VERSION_NAME}")
            .header("X-Lemon-Code", identity.code)
            .header("X-Lemon-Secret", identity.secret)
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun scheduleReconnect() {
        synchronized(lock) {
            if (closed) return
            socket = null
            val delay = (2_000L shl attempt.coerceAtMost(5)).coerceAtMost(60_000L)
            attempt++
            Log.info("[Online] presence lost, retrying in ${delay / 1000} s")
            Thread {
                Thread.sleep(delay)
                synchronized(lock) { if (!closed && socket == null) connect() }
            }.apply { name = "OnlinePresenceRetry"; isDaemon = true }.start()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                attempt = 0
                lastPresence?.let { webSocket.send(it.toString()) }
            }
            Log.info("[Online] presence connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (event.optString("t") == "ping") {
                webSocket.send("""{"t":"pong"}""")
                return
            }
            onEvent(event)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            synchronized(lock) {
                if (socket !== webSocket) return
                if (code == POLICY_VIOLATION) {
                    // A newer connection with the same identity: it has the presence now.
                    socket = null
                    Log.info("[Online] presence moved to another app or device")
                    return
                }
            }
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (response?.code == 401) {
                // The server doesn't know us (new database): register again, then reopen.
                synchronized(lock) { socket = null }
                onUnauthorized()
                return
            }
            if (socket === webSocket) scheduleReconnect()
        }
    }

    companion object {
        private const val CLIENT = "lemon"
        private const val POLICY_VIOLATION = 1008

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
