// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.features.aiconfig

import android.annotation.SuppressLint
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.lemon.lemon_emu.BuildConfig
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.features.settings.utils.SettingsFile
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.utils.NativeConfig
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Recommended settings" in a game's properties: asks the server, shows what would change and why,
 * and applies it to the game's own config when the person accepts.
 */
object AiConfigFlow {
    /** One change that passed the schema: [stored] is the config.ini text to write. */
    private data class Checked(
        val key: String,
        val stored: String,
        val currentStored: String,
        val reason: String,
        val experimental: Boolean
    )

    fun start(fragment: Fragment, game: Game, onChanged: () -> Unit) {
        val context = fragment.requireContext()
        var job: Job? = null
        val waiting = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_config_title)
            .setMessage(R.string.ai_config_asking)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .setOnCancelListener { job?.cancel() }
            .show()

        job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            val schema = AiSchema.load(context)
            val (request, current) = withContext(Dispatchers.IO) { buildRequest(context, schema, game) }
            val result = AiConfigClient.suggest(request)
            waiting.dismiss()
            when (result) {
                is AiConfigClient.Result.Ok -> showSuggestion(fragment, schema, game, current, result.suggestion, onChanged)
                is AiConfigClient.Result.ServerError -> showError(context, errorText(context, result))
                AiConfigClient.Result.NetworkError ->
                    showError(context, context.getString(R.string.ai_config_network_error))
            }
        }
    }

    /** Shows, once, that the last AI change was undone because the game closed while starting. */
    fun showNoticeIfAny(context: Context, game: Game) {
        val state = runCatching { AiConfigStore.state(game) }.getOrNull() ?: return
        if (state.state != AiConfigStore.REVERTED_CRASH) return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_config_title)
            .setMessage(R.string.ai_config_reverted_after_crash)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        AiConfigStore.clearNotice(game)
    }

    /** The request, and every schema setting's current value for this game (config.ini text). */
    private fun buildRequest(
        context: Context,
        schema: AiSchema,
        game: Game
    ): Pair<AiConfigClient.SuggestRequest, Map<String, String>> {
        val overridden = mutableMapOf<String, String>()
        val effective = mutableMapOf<String, String>()
        // Loading a game's config writes its file; asking must not leave one behind.
        val configFile = SettingsFile.getCustomSettingsFile(game)
        val hadConfig = configFile.exists()
        SettingsFile.loadCustomConfig(game)
        val device = try {
            for (entry in schema.settings) {
                val value = NativeConfig.getValueString(entry.key, false)
                effective[entry.key] = value
                if (!NativeConfig.usingGlobal(entry.key)) overridden[entry.key] = value
            }
            // With the game's config loaded, so the driver is the one this game uses.
            AiConfigClient.device(context)
        } finally {
            NativeConfig.unloadPerGameConfig()
            if (!hadConfig) configFile.delete()
        }
        val titleId = String.format(Locale.ROOT, "%016X", game.programId.toLongOrNull() ?: 0L)
        val request = AiConfigClient.SuggestRequest(
            client = "lemon",
            app_version = BuildConfig.VERSION_NAME,
            schema_version = schema.schema_version,
            title_id = titleId,
            title_name = game.title.take(128),
            game_version = game.version,
            language = Locale.getDefault().language,
            device = device,
            current = overridden
        )
        return request to effective
    }

    private fun check(
        schema: AiSchema,
        current: Map<String, String>,
        suggestion: AiConfigClient.Suggestion
    ): List<Checked> = suggestion.changes.mapNotNull { change ->
        val entry = schema.entry(change.key) ?: return@mapNotNull null
        // A risky setting only when the server marks it experimental (it has a precedent).
        if (entry.isRisky && !change.experimental) return@mapNotNull null
        val stored = schema.normalize(change.key, change.value) ?: return@mapNotNull null
        val now = current[change.key] ?: ""
        if (stored == now) return@mapNotNull null
        Checked(change.key, stored, now, change.reason, change.experimental || entry.isRisky)
    }.distinctBy { it.key }

    private fun showSuggestion(
        fragment: Fragment,
        schema: AiSchema,
        game: Game,
        current: Map<String, String>,
        suggestion: AiConfigClient.Suggestion,
        onChanged: () -> Unit
    ) {
        val context = fragment.context ?: return
        val changes = check(schema, current, suggestion)
        if (changes.isEmpty()) {
            val text = buildString {
                append(context.getString(R.string.ai_config_nothing_to_change))
                if (suggestion.notes.isNotBlank()) append("\n\n").append(suggestion.notes)
            }
            showError(context, text)
            return
        }

        val message = SpannableStringBuilder()
        if (suggestion.notes.isNotBlank()) message.append(suggestion.notes).append("\n\n")
        for (c in changes) {
            val start = message.length
            message.append(title(context, schema, c.key))
            message.setSpan(StyleSpan(Typeface.BOLD), start, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (c.experimental) message.append(" · ").append(context.getString(R.string.ai_config_experimental))
            message.append("\n")
                .append(valueText(context, schema, c.key, c.currentStored))
                .append("  →  ")
                .append(valueText(context, schema, c.key, c.stored))
            if (c.reason.isNotBlank()) message.append("\n").append(c.reason)
            message.append("\n\n")
        }
        message.append(context.getString(R.string.ai_config_undo_hint))

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_config_title)
            .setMessage(message)
            .setPositiveButton(R.string.ai_config_apply) { _, _ ->
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AiConfigStore.apply(game, suggestion.answer_id, changes.associate { it.key to it.stored })
                    }
                    Toast.makeText(context, R.string.ai_config_applied, Toast.LENGTH_LONG).show()
                    onChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The setting's name in the app's language: ai_setting_<key> for settings the settings screens
     * do not show, else the title those screens use, else the schema's English title.
     */
    @SuppressLint("DiscouragedApi")
    private fun title(context: Context, schema: AiSchema, key: String): String {
        val res = context.resources
        val own = res.getIdentifier("ai_setting_$key", "string", context.packageName)
        if (own != 0) return context.getString(own)
        val entry = schema.entry(key)
        entry?.ui_title_res?.let { name ->
            val id = res.getIdentifier(name, "string", context.packageName)
            if (id != 0) return context.getString(id)
        }
        return entry?.ui_title ?: key
    }

    /** A value as a person reads it: On/Off, an enum's name, or the number. */
    private fun valueText(context: Context, schema: AiSchema, key: String, stored: String): String =
        when (stored) {
            "true" -> context.getString(R.string.ai_config_on)
            "false" -> context.getString(R.string.ai_config_off)
            else -> schema.describe(key, stored)
        }

    private fun errorText(context: Context, error: AiConfigClient.Result.ServerError): String =
        when (error.code) {
            "rate_limited" -> context.getString(R.string.ai_config_rate_limited)
            "schema_unknown" -> context.getString(R.string.ai_config_update_app)
            "ai_unavailable" -> context.getString(R.string.ai_config_unavailable)
            else -> context.getString(R.string.ai_config_server_error, error.code)
        }

    private fun showError(context: Context, text: String) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_config_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
