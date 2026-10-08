// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.nextendo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.lemon.lemon_emu.LemonApplication
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.features.settings.model.BooleanSetting
import dev.lemon.lemon_emu.utils.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Nextendo Network account: browser sign-in (OAuth 2.0 + PKCE, the password is only ever typed on
 * nextendo.network) and the 24-hour game token the emulated account service presents to the games.
 *
 * Only the refresh token is stored, encrypted with a key kept in the Android Keystore. The PID and
 * the tokens are credentials: never log or display them.
 */
object NextendoAccount {
    // Public client ID issued by Nextendo for Lemon. A public PKCE client has no secret, so the ID
    // can ship in the app (registered on nextendo.network/developers).
    private const val CLIENT_ID = "nxc_wcZ5At_lLbs-"

    private const val BASE_URL = "https://nextendo.network"
    private const val SCOPES = "identity friends presence game.matchmaking"
    private const val PREFS = "nextendo_account"
    private const val PREF_REFRESH_TOKEN = "refresh_token"
    private const val PREF_USERNAME = "username"
    private const val KEY_ALIAS = "lemon_nextendo_account"
    private const val SIGN_IN_TIMEOUT_MS = 5 * 60 * 1000

    val isAvailable: Boolean
        get() = CLIENT_ID.isNotEmpty()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private class GameToken(
        val pid: Long,
        val username: String,
        val nexToken: String,
        val expiresAtMs: Long
    )

    @Volatile
    private var signInRunning = false

    @Volatile
    private var gameToken: GameToken? = null

    private val prefs
        get() = LemonApplication.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The signed-in account's username, or null when signed out. */
    fun username(): String? {
        if (!prefs.contains(PREF_REFRESH_TOKEN)) {
            return null
        }
        return prefs.getString(PREF_USERNAME, null) ?: ""
    }

    /**
     * Opens the Nextendo sign-in page in the browser and waits for it to come back to a one-off
     * loopback address. [onDone] gets null on success or a short reason, on a background thread.
     */
    fun signIn(activity: Activity, onDone: (String?) -> Unit) {
        if (signInRunning) {
            return
        }
        signInRunning = true
        thread(name = "NextendoSignIn") {
            val error = try {
                runSignIn(activity)
            } catch (e: Exception) {
                Log.warning("[Nextendo] Sign-in failed: ${e.javaClass.simpleName}")
                e.javaClass.simpleName
            } finally {
                signInRunning = false
            }
            onDone(error)
        }
    }

    fun signOut() {
        prefs.edit {
            remove(PREF_REFRESH_TOKEN)
            remove(PREF_USERNAME)
        }
        gameToken = null
        NativeLibrary.clearNextendoSession()
    }

    /**
     * Called on the emulation thread right before a game boots: hands the game token to the
     * emulated account service, fetching a fresh one when needed. Never throws.
     */
    fun prepareForBoot() {
        if (!isAvailable || !BooleanSetting.ENABLE_NEXTENDO.getBoolean() || username() == null) {
            NativeLibrary.clearNextendoSession()
            return
        }
        try {
            val token = gameToken?.takeIf {
                it.expiresAtMs - System.currentTimeMillis() > TimeUnit.HOURS.toMillis(1)
            } ?: fetchGameToken()
            if (token == null) {
                NativeLibrary.clearNextendoSession()
                return
            }
            NativeLibrary.setNextendoSession(token.pid, token.username, token.nexToken)
            Log.info("[Nextendo] Game token ready")
        } catch (e: Exception) {
            Log.warning("[Nextendo] Could not get a game token: ${e.javaClass.simpleName}")
            NativeLibrary.clearNextendoSession()
        }
    }

    private fun runSignIn(activity: Activity): String? {
        val verifier = randomUrlSafe(32)
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        val state = randomUrlSafe(24)

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = SIGN_IN_TIMEOUT_MS
            val redirectUri = "http://127.0.0.1:${server.localPort}/callback"
            val authorizeUrl = Uri.parse("$BASE_URL/api/oauth/authorize").buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
            activity.runOnUiThread {
                activity.startActivity(Intent(Intent.ACTION_VIEW, authorizeUrl))
            }

            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    return "timeout"
                }
                val callback = try {
                    readCallback(socket)
                } finally {
                    socket.close()
                }
                // Browsers also ask for /favicon.ico and the like; wait for the real callback.
                callback ?: continue

                callback.getQueryParameter("error")?.let { return it }
                if (callback.getQueryParameter("state") != state) {
                    return "state"
                }
                val code = callback.getQueryParameter("code") ?: return "no code"
                return finishSignIn(code, redirectUri, verifier)
            }
        }
    }

    /** Reads one loopback request; returns its URI when it is the OAuth callback. */
    private fun readCallback(socket: Socket): Uri? {
        socket.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val target = reader.readLine()?.split(' ')?.getOrNull(1)
        val uri = target?.let { Uri.parse("http://127.0.0.1$it") }
        val isCallback = uri?.path == "/callback"
        val ok = isCallback && uri?.getQueryParameter("code") != null
        val body = if (!isCallback) {
            ""
        } else if (ok) {
            "<h2>Lemon: sesi&oacute;n iniciada / signed in</h2>" +
                "<p>Ya puedes volver a Lemon. You can go back to Lemon now.</p>"
        } else {
            "<h2>Lemon: no se inici&oacute; sesi&oacute;n / sign-in not completed</h2>"
        }
        val page = "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width\"></head>" +
            "<body style=\"font-family:sans-serif;text-align:center;padding:40px\">" +
            body + "</body></html>"
        val status = if (isCallback) "200 OK" else "404 Not Found"
        val bytes = page.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII)
            )
            write(bytes)
            flush()
        }
        return if (isCallback) uri else null
    }

    private fun finishSignIn(code: String, redirectUri: String, verifier: String): String? {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", CLIENT_ID)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", verifier)
            .build()
        val tokens = postToken(form) ?: return "token"
        val accessToken = tokens.optString("access_token")
        val refreshToken = tokens.optString("refresh_token")
        if (accessToken.isEmpty() || refreshToken.isEmpty()) {
            return "token"
        }
        saveRefreshToken(refreshToken)

        val token = requestGameToken(accessToken)
        prefs.edit { putString(PREF_USERNAME, token?.username ?: "") }
        gameToken = token
        return if (token == null) "game token" else null
    }

    /** Refreshes the access token (rotating the stored refresh token), then gets a game token. */
    private fun fetchGameToken(): GameToken? {
        val refreshToken = loadRefreshToken() ?: return null
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()
        val tokens = postToken(form, signOutOnRejection = true) ?: return null
        tokens.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { saveRefreshToken(it) }
        val accessToken = tokens.optString("access_token").takeIf { it.isNotEmpty() }
            ?: return null
        return requestGameToken(accessToken)?.also { token ->
            gameToken = token
            prefs.edit { putString(PREF_USERNAME, token.username) }
        }
    }

    private fun postToken(form: FormBody, signOutOnRejection: Boolean = false): JSONObject? {
        val request = Request.Builder()
            .url("$BASE_URL/api/oauth/token")
            .header("X-Nextendo-Client-Id", CLIENT_ID)
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.warning("[Nextendo] Token request refused (HTTP ${response.code})")
                // A refused refresh token is revoked or expired: sign out instead of retrying.
                if (signOutOnRejection && response.code in 400..401) {
                    signOut()
                }
                return null
            }
            return JSONObject(body)
        }
    }

    private fun requestGameToken(accessToken: String): GameToken? {
        val request = Request.Builder()
            .url("$BASE_URL/api/nex-token")
            .header("Authorization", "Bearer $accessToken")
            .header("X-Nextendo-Client-Id", CLIENT_ID)
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.warning("[Nextendo] Game token refused (HTTP ${response.code})")
                return null
            }
            val json = JSONObject(body)
            val nexToken = json.optString("nex_token")
            val pid = json.optLong("pid")
            if (nexToken.isEmpty() || pid == 0L) {
                return null
            }
            val expiresIn = json.optLong("expires_in", TimeUnit.HOURS.toSeconds(24))
            return GameToken(
                pid = pid,
                username = json.optString("username"),
                nexToken = nexToken,
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000
            )
        }
    }

    private fun saveRefreshToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit { putString(PREF_REFRESH_TOKEN, stored) }
    }

    private fun loadRefreshToken(): String? {
        val stored = prefs.getString(PREF_REFRESH_TOKEN, null) ?: return null
        return try {
            val (iv, encrypted) = stored.split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // The Keystore key is gone (app data restored on another device, for example).
            Log.warning("[Nextendo] Stored sign-in unreadable, signing out")
            signOut()
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun randomUrlSafe(bytes: Int): String =
        base64Url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
