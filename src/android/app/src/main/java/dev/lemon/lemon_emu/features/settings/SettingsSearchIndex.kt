// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.features.settings

import android.content.Context
import dev.lemon.lemon_emu.features.settings.model.Settings.MenuTag
import dev.lemon.lemon_emu.features.settings.model.view.SettingsItem
import dev.lemon.lemon_emu.features.settings.model.view.SubmenuSetting
import dev.lemon.lemon_emu.features.settings.ui.SettingsFragmentPresenter
import dev.lemon.lemon_emu.features.settings.ui.SettingsViewModel
import java.text.Normalizer

/**
 * Search over every setting, however deep it sits. The index walks the same sections the menus
 * show (the same builders, in the user's language), so a new option is searchable without
 * touching this file.
 */
object SettingsSearchIndex {
    class Entry(
        val title: String,
        val description: String,
        /** Category and section titles leading to the entry. */
        val path: List<String>,
        /** The section that shows the entry. */
        val menuTag: MenuTag,
        /** Identifies the row inside its section (setting key, or the title). */
        val anchor: String,
        val iconId: Int,
        private val keywords: String
    ) {
        internal val haystackTitle = normalize(title)
        internal val haystack = normalize("$title $description ${path.joinToString(" ")} $keywords")
    }

    /** The row a search result opened, picked up by the section once it is shown. */
    var pendingTarget: Pair<MenuTag, String>? = null

    private var cached: List<Entry>? = null
    private var cachedLocale: String? = null

    // Sections whose rows are not options worth finding (button mappings, shader cards).
    private val skipped = setOf(
        MenuTag.SECTION_INPUT_PLAYER_ONE,
        MenuTag.SECTION_INPUT_PLAYER_TWO,
        MenuTag.SECTION_INPUT_PLAYER_THREE,
        MenuTag.SECTION_INPUT_PLAYER_FOUR,
        MenuTag.SECTION_INPUT_PLAYER_FIVE,
        MenuTag.SECTION_INPUT_PLAYER_SIX,
        MenuTag.SECTION_INPUT_PLAYER_SEVEN,
        MenuTag.SECTION_INPUT_PLAYER_EIGHT,
        MenuTag.SECTION_FREEDRENO,
        MenuTag.SECTION_POST_PROCESSING
    )

    private val skippedTypes = setOf(
        SettingsItem.TYPE_HEADER,
        SettingsItem.TYPE_FX_TOOLBAR,
        SettingsItem.TYPE_FX_PRESET,
        SettingsItem.TYPE_FX_SHADER,
        SettingsItem.TYPE_FX_BUTTON
    )

    // Words people search with that the titles don't use, in English and Spanish.
    private val extraKeywords = mapOf(
        "show_performance_overlay" to "fps frames framerate rendimiento",
        "show_fps" to "fps frames framerate",
        "gpu_accuracy" to "black screen pantalla negra glitches errores graficos",
        "resolution_setup" to "upscale escala 720p 1080p 4k",
        "use_asynchronous_shaders" to "stutter tirones shader cache",
        "aspect_ratio" to "widescreen panoramica franjas bars stretch estirar 21:9",
        "use_docked_mode" to "dock tv portatil handheld",
        "enable_nextendo" to "online internet multiplayer mario kart",
        "language_index" to "idioma language",
        "bcn_astc_recompression" to "mali textures texturas memory memoria",
        "cpu_backend" to "nce dynarmic jit"
    )

    fun search(context: Context, query: String): List<Entry> {
        val terms = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) {
            return emptyList()
        }
        return index(context)
            .filter { entry -> terms.all { entry.haystack.contains(it) } }
            .sortedByDescending { entry ->
                when {
                    entry.haystackTitle.startsWith(terms.first()) -> 3
                    terms.all { entry.haystackTitle.contains(it) } -> 2
                    else -> 1
                }
            }
    }

    /** Drop the index, e.g. after the app language changes. */
    fun invalidate() {
        cached = null
    }

    private fun index(context: Context): List<Entry> {
        val locale = context.resources.configuration.locales[0].toLanguageTag()
        cached?.takeIf { cachedLocale == locale }?.let { return it }

        val entries = mutableListOf<Entry>()
        val visited = mutableSetOf<MenuTag>()
        for (category in SettingsCategories.all) {
            val categoryTitle = context.getString(category.titleId)
            entries += Entry(
                title = categoryTitle,
                description = context.getString(category.descriptionId),
                path = emptyList(),
                menuTag = category.menuTag,
                anchor = "",
                iconId = category.iconId,
                keywords = ""
            )
            walk(category.menuTag, listOf(categoryTitle), entries, visited)
        }
        cached = entries
        cachedLocale = locale
        return entries
    }

    private fun walk(
        tag: MenuTag,
        path: List<String>,
        entries: MutableList<Entry>,
        visited: MutableSet<MenuTag>
    ) {
        if (tag in skipped || !visited.add(tag)) {
            return
        }
        val items = try {
            SettingsFragmentPresenter(SettingsViewModel(), null, tag, null).buildSettingsList(tag)
        } catch (e: Exception) {
            return
        }
        for (item in items) {
            if (item.type in skippedTypes || item.title.isBlank()) {
                continue
            }
            val key = item.setting.key
            entries += Entry(
                title = item.title,
                description = item.description,
                path = path,
                menuTag = tag,
                anchor = key.ifEmpty { item.title },
                iconId = (item as? SubmenuSetting)?.iconId ?: 0,
                keywords = extraKeywords[key].orEmpty()
            )
            if (item is SubmenuSetting) {
                walk(item.menuKey, path + item.title, entries, visited)
            }
        }
    }

    /** Lower case, accents dropped: "Resolución" and "resolucion" match. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}
