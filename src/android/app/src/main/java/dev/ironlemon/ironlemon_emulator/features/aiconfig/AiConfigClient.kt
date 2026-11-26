// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.features.aiconfig

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dev.ironlemon.ironlemon_emulator.BuildConfig
import dev.ironlemon.ironlemon_emulator.utils.GpuDriverHelper
import dev.ironlemon.ironlemon_emulator.utils.GpuDriverMetadata
import dev.ironlemon.ironlemon_emulator.utils.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Asks the Lemon AI server for per-game settings. The server holds the AI key and the cache; the app
 * sends only the game and what the device is (no account, no personal data).
 */
object AiConfigClient {
    @Serializable
    data class Device(
        val soc: String,
        val gpu: String,
        val gpu_family: String,
        val driver: String,
        val vulkan: String,
        val ram_gb: Int,
        val android_api: Int
    )

    @Serializable
    data class SuggestRequest(
        val client: String,
        val app_version: String,
        val schema_version: Int,
        val title_id: String,
        /** The game's name (public); the model answers far better knowing which game it is. */
        val title_name: String,
        val game_version: String,
        /** For the reasons and notes in the answer (ISO 639-1, e.g. "es"). */
        val language: String,
        val device: Device,
        val current: Map<String, String>
    )

    @Serializable
    data class Change(
        val key: String,
        val value: JsonElement,
        val reason: String = "",
        val experimental: Boolean = false
    )

    @Serializable
    data class Suggestion(
        val answer_id: String,
        val source: String = "",
        val changes: List<Change> = emptyList(),
        val notes: String = ""
    )

    @Serializable
    private data class Feedback(val answer_id: String, val outcome: String)

    @Serializable
    private data class ErrorBody(val error: ErrorInfo) {
        @Serializable
        data class ErrorInfo(val code: String = "", val message: String = "")
    }

    sealed interface Result {
        data class Ok(val suggestion: Suggestion) : Result

        /** The server answered with an error ([code] from its body, e.g. rate_limited). */
        data class ServerError(val code: String, val retryAfterSeconds: Long?) : Result

        data object NetworkError : Result
    }

    enum class Outcome(val wire: String) {
        WORKED("worked"),
        WORSE("worse"),
        CRASHED("crashed"),
        NO_BOOT("no_boot"),
        REVERTED("reverted")
    }

    private const val MAX_BYTES = 256L * 1024

    // coerceInputValues: a null where a list or text is expected becomes the default (empty).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private const val CONNECT_TIMEOUT_MS = 15_000
    // The model can take a while when nothing is cached.
    private const val READ_TIMEOUT_MS = 90_000

    private class Response(val code: Int, val text: String?, val retryAfter: Long?)

    /** POSTs [body] as JSON. The answer is read only up to [MAX_BYTES]; larger means null text. */
    private fun post(path: String, body: String): Response {
        val connection = URL("${BuildConfig.AI_CONFIG_URL}$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                val data = input.readNBytesCompat(MAX_BYTES.toInt() + 1)
                if (data.size > MAX_BYTES) null else String(data, Charsets.UTF_8)
            }
            return Response(code, text, connection.getHeaderField("Retry-After")?.toLongOrNull())
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Off the main thread: the first call per system build asks the Vulkan driver. */
    fun device(context: Context): Device {
        // [version, name, GPU model] of the system driver; asked once per system build, then cached.
        val info = runCatching { GpuDriverHelper.querySystemDriverInfo() }.getOrNull()
        val vulkan = info?.getOrNull(0).orEmpty()
        val gpu = info?.getOrNull(2).orEmpty()
        val custom = GpuDriverHelper.customDriverSettingData
        val driver = if (custom == GpuDriverMetadata()) {
            "system $vulkan"
        } else {
            listOfNotNull(custom.name, custom.version).joinToString(" ")
        }
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        return Device(
            soc = soc,
            gpu = gpu,
            gpu_family = gpuFamily(gpu),
            driver = driver.trim(),
            vulkan = vulkan,
            ram_gb = ((memory.totalMem + (1L shl 29)) shr 30).toInt(),
            android_api = Build.VERSION.SDK_INT
        )
    }

    /** "Adreno (TM) 830" -> "adreno8xx", "Mali-G925-Immortalis" -> "mali-g9xx", else the name. */
    fun gpuFamily(gpu: String): String {
        val lower = gpu.lowercase()
        Regex("adreno\\D*(\\d)\\d\\d").find(lower)?.let { return "adreno${it.groupValues[1]}xx" }
        Regex("mali-?([gt])(\\d)\\d+").find(lower)?.let {
            return "mali-${it.groupValues[1]}${it.groupValues[2]}xx"
        }
        if ("xclipse" in lower) return "xclipse"
        if ("powervr" in lower) return "powervr"
        return lower.trim()
    }

    suspend fun suggest(request: SuggestRequest): Result = withContext(Dispatchers.IO) {
        val body = json.encodeToString(SuggestRequest.serializer(), request)
        val response = try {
            post("/ai/game-config", body)
        } catch (e: IOException) {
            Log.warning("[AiConfig] Could not reach the server: ${e.javaClass.simpleName}")
            return@withContext Result.NetworkError
        }
        val text = response.text
        if (response.code !in 200..299) {
            val code = text?.let {
                runCatching { json.decodeFromString(ErrorBody.serializer(), it).error.code }.getOrNull()
            } ?: "http_${response.code}"
            Log.warning("[AiConfig] Server answered ${response.code} ($code)")
            return@withContext Result.ServerError(code, response.retryAfter)
        }
        val suggestion = text?.let {
            runCatching { json.decodeFromString(Suggestion.serializer(), it) }.getOrNull()
        } ?: return@withContext Result.ServerError("bad_answer", null)
        Result.Ok(suggestion)
    }

    /** Best effort: a lost report only means the server learns a little less. */
    suspend fun feedback(answerId: String, outcome: Outcome) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(Feedback.serializer(), Feedback(answerId, outcome.wire))
        try {
            post("/ai/game-config/feedback", body)
        } catch (e: IOException) {
            Log.warning("[AiConfig] Could not send feedback: ${e.javaClass.simpleName}")
        }
    }
}
