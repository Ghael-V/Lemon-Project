// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.features.aiconfig

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dev.lemon.lemon_emu.BuildConfig
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.utils.GpuDriverHelper
import dev.lemon.lemon_emu.utils.GpuDriverMetadata
import dev.lemon.lemon_emu.utils.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Asks the Lemon server for per-game settings. The server holds the AI key and the cache; the app
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

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // The model can take a while when nothing is cached.
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    fun device(context: Context): Device {
        val gpu = runCatching { NativeLibrary.getGpuModel() }.getOrDefault("")
        val vulkan = runCatching { NativeLibrary.getVulkanDriverVersion() }.getOrDefault("")
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
        val body = json.encodeToString(SuggestRequest.serializer(), request).toRequestBody(jsonType)
        val call = Request.Builder().url("${BuildConfig.AI_CONFIG_URL}/ai/game-config").post(body).build()
        try {
            client.newCall(call).execute().use { response ->
                val text = response.body?.source()?.let { source ->
                    source.request(MAX_BYTES + 1)
                    if (source.buffer.size > MAX_BYTES) null else source.buffer.readUtf8()
                }
                if (!response.isSuccessful) {
                    val code = text?.let {
                        runCatching { json.decodeFromString(ErrorBody.serializer(), it).error.code }
                            .getOrNull()
                    } ?: "http_${response.code}"
                    Log.warning("[AiConfig] Server answered ${response.code} ($code)")
                    return@withContext Result.ServerError(
                        code,
                        response.header("Retry-After")?.toLongOrNull()
                    )
                }
                val suggestion = text?.let {
                    runCatching { json.decodeFromString(Suggestion.serializer(), it) }.getOrNull()
                } ?: return@withContext Result.ServerError("bad_answer", null)
                Result.Ok(suggestion)
            }
        } catch (e: Exception) {
            Log.warning("[AiConfig] Could not reach the server: ${e.javaClass.simpleName}")
            Result.NetworkError
        }
    }

    /** Best effort: a lost report only means the server learns a little less. */
    suspend fun feedback(answerId: String, outcome: Outcome) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(Feedback.serializer(), Feedback(answerId, outcome.wire))
            .toRequestBody(jsonType)
        val call = Request.Builder()
            .url("${BuildConfig.AI_CONFIG_URL}/ai/game-config/feedback")
            .post(body)
            .build()
        try {
            client.newCall(call).execute().close()
        } catch (e: Exception) {
            Log.warning("[AiConfig] Could not send feedback: ${e.javaClass.simpleName}")
        }
    }
}
