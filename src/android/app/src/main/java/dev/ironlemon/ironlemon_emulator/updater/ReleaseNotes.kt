// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.updater

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Turns a release's notes (the Markdown the release page shows) into text for the update dialog:
 * headings and **bold** become bold, "- " items become bullets, links keep only their text, and
 * the Markdown marks themselves are dropped. A first heading that only repeats the version is
 * left out, since the dialog's title already says it.
 */
object ReleaseNotes {
    private val bold = SpanStyle(fontWeight = FontWeight.Bold)
    private val heading = Regex("^#{1,6}\\s+(.*)$")
    private val item = Regex("^\\s*[-*+]\\s+(.*)$")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val code = Regex("`([^`]*)`")
    private val strong = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")

    fun format(notes: String, version: String): AnnotatedString {
        val lines = notes.replace("\r\n", "\n").trim().lines().toMutableList()
        val first = lines.firstOrNull()?.let { heading.find(it.trim()) }
        if (first != null && version.isNotEmpty() && first.groupValues[1].contains(version.removePrefix("v"))) {
            lines.removeAt(0)
            while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
        }
        return buildAnnotatedString {
            var blank = false
            lines.forEach { raw ->
                val line = raw.trimEnd()
                if (line.isBlank()) {
                    blank = true
                    return@forEach
                }
                if (length > 0) append(if (blank) "\n\n" else "\n")
                blank = false
                val h = heading.find(line.trim())
                val i = item.find(line)
                when {
                    h != null -> withStyle(bold) { append(plain(h.groupValues[1])) }
                    i != null -> {
                        append("• ")
                        inline(this, i.groupValues[1])
                    }
                    else -> inline(this, line.trim())
                }
            }
        }
    }

    /** Text without any Markdown marks. */
    private fun plain(text: String): String =
        strong.replace(code.replace(link.replace(text, "$1"), "$1")) { it.groupValues[1] + it.groupValues[2] }

    /** Appends [text] with its **bold** parts in bold. */
    private fun inline(builder: AnnotatedString.Builder, text: String) {
        val clean = code.replace(link.replace(text, "$1"), "$1")
        var last = 0
        for (match in strong.findAll(clean)) {
            builder.append(clean.substring(last, match.range.first))
            builder.withStyle(bold) { append(match.groupValues[1] + match.groupValues[2]) }
            last = match.range.last + 1
        }
        builder.append(clean.substring(last))
    }
}
