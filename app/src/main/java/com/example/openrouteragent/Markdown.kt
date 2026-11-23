package com.example.openrouteragent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * A small Markdown reader for agent replies: GitHub-style blocks (headings, paragraphs, fenced code, quotes,
 * nested and task lists, tables, rules) and inline emphasis, code, strikethrough and links.
 * Line breaks inside a paragraph are kept, as chat replies use them for layout.
 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdListItem>) : MdBlock
    data class Table(val header: List<String>, val alignments: List<MdAlign>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

internal data class MdListItem(val blocks: List<MdBlock>, val checked: Boolean? = null)

internal enum class MdAlign { Start, Center, End }

internal fun parseMarkdown(source: String): List<MdBlock> =
    parseBlocks(source.replace("\r\n", "\n").replace('\r', '\n').split('\n').map(::expandLeadingTabs))

private fun expandLeadingTabs(line: String): String {
    if (!line.startsWith('\t') && !line.startsWith(" \t")) return line
    val out = StringBuilder()
    var index = 0
    while (index < line.length && (line[index] == ' ' || line[index] == '\t')) {
        if (line[index] == '\t') repeat(4 - out.length % 4) { out.append(' ') } else out.append(' ')
        index++
    }
    return out.append(line, index, line.length).toString()
}

private val FENCE = Regex("""^( {0,3})(`{3,}|~{3,})\s*([^`\s]*)[^`]*$""")
private val HEADING = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?[ \t]*$""")
private val HEADING_CLOSE = Regex("""(?:^|[ \t]+)#+$""")
private val RULE = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
private val BULLET = Regex("""^( *)([-*+])(?:([ \t]+)(.*))?$""")
private val ORDERED = Regex("""^( *)(\d{1,9})([.)])(?:([ \t]+)(.*))?$""")
private val QUOTE = Regex("""^ {0,3}> ?(.*)$""")
private val TABLE_DIVIDER = Regex("""^\s*\|?\s*:?-+:?\s*(?:\|\s*:?-+:?\s*)*\|?\s*$""")
private val TASK = Regex("""^\[([ xX])][ \t]+""")

private fun indentOf(line: String) = line.length - line.trimStart(' ').length

private class ListMarker(val indent: Int, val ordered: Boolean, val number: Int, val contentColumn: Int, val content: String)

private fun listMarker(line: String): ListMarker? {
    if (RULE.matches(line)) return null
    BULLET.matchEntire(line)?.let { m ->
        val indent = m.groupValues[1].length
        val gap = m.groupValues[3].length.let { if (it in 1..4) it else 1 }
        return ListMarker(indent, false, 0, indent + 1 + gap, m.groupValues[4])
    }
    ORDERED.matchEntire(line)?.let { m ->
        val indent = m.groupValues[1].length
        val gap = m.groupValues[4].length.let { if (it in 1..4) it else 1 }
        val number = m.groupValues[2].toIntOrNull() ?: return null
        return ListMarker(indent, true, number, indent + m.groupValues[2].length + 1 + gap, m.groupValues[5])
    }
    return null
}

private fun isTableStart(lines: List<String>, index: Int): Boolean =
    index + 1 < lines.size && '|' in lines[index] && TABLE_DIVIDER.matches(lines[index + 1]) &&
        ('|' in lines[index + 1] || splitRow(lines[index]).size == 1) &&
        splitRow(lines[index]).size == splitRow(lines[index + 1]).size

/** Whether [line] begins a block that ends the paragraph above it. */
private fun interruptsParagraph(lines: List<String>, index: Int): Boolean {
    val line = lines[index]
    return line.isBlank() || FENCE.matches(line) || HEADING.matches(line) || RULE.matches(line) ||
        QUOTE.matches(line) || listMarker(line)?.takeIf { it.content.isNotBlank() } != null || isTableStart(lines, index)
}

private fun parseBlocks(lines: List<String>): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }
        val fence = FENCE.matchEntire(line)
        if (fence != null) {
            val indent = fence.groupValues[1].length
            val close = closingFence(fence.groupValues[2], lineIndent = 3)
            val body = mutableListOf<String>()
            i++
            while (i < lines.size && !close.matches(lines[i])) {
                body += lines[i].drop(minOf(indent, indentOf(lines[i])))
                i++
            }
            i++ // Skips the closing fence; an unclosed fence (still streaming) runs to the end.
            blocks += MdBlock.Code(fence.groupValues[3], body.joinToString("\n"))
            continue
        }
        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].replace(HEADING_CLOSE, "").trim())
            i++
            continue
        }
        if (RULE.matches(line)) {
            blocks += MdBlock.Rule
            i++
            continue
        }
        if (QUOTE.matches(line)) {
            val inner = mutableListOf<String>()
            while (i < lines.size) {
                val quoted = QUOTE.matchEntire(lines[i])
                // A line without '>' continues the quote's paragraph until a blank line or a new block.
                if (quoted != null) inner += quoted.groupValues[1]
                else if (inner.lastOrNull()?.isNotBlank() == true && !interruptsParagraph(lines, i)) inner += lines[i]
                else break
                i++
            }
            blocks += MdBlock.Quote(parseBlocks(inner))
            continue
        }
        val marker = listMarker(line)
        if (marker != null) {
            i = parseList(lines, i, marker, blocks)
            continue
        }
        if (isTableStart(lines, i)) {
            val header = splitRow(line)
            val alignments = splitRow(lines[i + 1]).map {
                when {
                    it.startsWith(':') && it.endsWith(':') -> MdAlign.Center
                    it.endsWith(':') -> MdAlign.End
                    else -> MdAlign.Start
                }
            }
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i] && !interruptsBody(lines[i])) {
                val cells = splitRow(lines[i])
                rows += List(header.size) { cells.getOrElse(it) { "" } }
                i++
            }
            blocks += MdBlock.Table(header, alignments, rows)
            continue
        }
        val paragraph = mutableListOf(line)
        i++
        while (i < lines.size && !interruptsParagraph(lines, i)) paragraph += lines[i++]
        blocks += MdBlock.Paragraph(joinParagraph(paragraph))
    }
    return blocks
}

/** Backtick and tilde need no regex escaping; a closing fence is at least as long as the opening one. */
private fun closingFence(fence: String, lineIndent: Int?): Regex =
    Regex("""^ ${if (lineIndent == null) "*" else "{0,$lineIndent}"}${fence[0]}{${fence.length},}\s*$""")

private fun interruptsBody(line: String) = FENCE.matches(line) || HEADING.matches(line) || QUOTE.matches(line)

private fun joinParagraph(lines: List<String>): String = lines.joinToString("\n") { line ->
    // A trailing backslash marks a hard break, which a kept newline already gives.
    val trimmed = line.trim()
    if (trimmed.endsWith("\\") && !trimmed.endsWith("\\\\")) trimmed.dropLast(1) else trimmed
}

/** Parses one list from [start] into [out] and returns the index of the first line after it. */
private fun parseList(lines: List<String>, start: Int, first: ListMarker, out: MutableList<MdBlock>): Int {
    val items = mutableListOf<MdListItem>()
    var i = start
    var marker: ListMarker? = first
    while (marker != null) {
        val body = mutableListOf(marker.content)
        val column = marker.contentColumn
        var fenced: Regex? = null
        i++
        while (i < lines.size) {
            val line = lines[i]
            val indent = indentOf(line)
            if (fenced != null) {
                body += line.drop(minOf(indent, column))
                if (fenced.matches(line)) fenced = null
                i++
                continue
            }
            if (line.isBlank()) {
                // A blank line stays in the item only when indented content follows; siblings are found below.
                val next = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: break
                if (indentOf(lines[next]) < column) break
                body += ""
                i++
                continue
            }
            val nested = listMarker(line)
            if (nested != null && indent <= first.indent + 1) break
            if (indent >= column || (nested != null && indent > first.indent + 1)) {
                val stripped = line.drop(minOf(indent, column))
                FENCE.matchEntire(stripped)?.let { fenced = closingFence(it.groupValues[2], lineIndent = null) }
                body += stripped
                i++
                continue
            }
            // A lazy continuation line joins the item's paragraph.
            if (body.lastOrNull()?.isNotBlank() == true && !interruptsParagraph(lines, i)) {
                body += line.trim()
                i++
                continue
            }
            break
        }
        val task = TASK.find(body[0])
        if (task != null) body[0] = body[0].substring(task.range.last + 1)
        items += MdListItem(parseBlocks(body), task?.let { it.groupValues[1] != " " })
        // Blank lines between siblings are skipped here; the loop above already confirmed a sibling follows.
        var next = i
        while (next < lines.size && lines[next].isBlank()) next++
        val sibling = lines.getOrNull(next)?.let(::listMarker)
        marker = sibling?.takeIf { it.ordered == first.ordered && it.indent <= first.indent + 1 }
        if (marker != null) i = next
    }
    out += MdBlock.ListBlock(first.ordered, first.number, items)
    return i
}

private fun splitRow(line: String): List<String> {
    var row = line.trim()
    if (row.startsWith('|')) row = row.substring(1)
    if (row.endsWith('|') && !row.endsWith("\\|")) row = row.dropLast(1)
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var code = false
    var index = 0
    while (index < row.length) {
        val c = row[index]
        when {
            c == '\\' && row.getOrNull(index + 1) == '|' -> { cell.append('|'); index++ }
            c == '`' -> { code = !code; cell.append(c) }
            c == '|' && !code -> { cells += cell.toString().trim(); cell.clear() }
            else -> cell.append(c)
        }
        index++
    }
    cells += cell.toString().trim()
    return cells
}

/** Colors for inline Markdown; links also carry a [MARKDOWN_URL_TAG] annotation for tap handling. */
internal data class MarkdownInlineStyle(val link: Color, val codeBackground: Color)

internal const val MARKDOWN_URL_TAG = "markdown-url"

private val OPENABLE_URL = Regex("""^(?:https?://|mailto:|tel:)\S+$""", RegexOption.IGNORE_CASE)
private val BARE_URL = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
private val HTML_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private const val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

internal fun markdownInline(text: String, style: MarkdownInlineStyle): AnnotatedString =
    buildAnnotatedString { InlineWriter(this, style).write(text.replace(HTML_BREAK, "\n")) }

private class InlineWriter(private val out: AnnotatedString.Builder, private val style: MarkdownInlineStyle) {
    fun write(text: String) {
        var i = 0
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) out.append(plain.toString())
            plain.clear()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in PUNCTUATION -> {
                    plain.append(text[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val run = runLength(text, i, '`')
                    val close = findCodeClose(text, i + run, run)
                    if (close < 0) {
                        plain.append(text, i, i + run)
                        i += run
                    } else {
                        flush()
                        var code = text.substring(i + run, close).replace('\n', ' ')
                        if (code.length > 2 && code.startsWith(' ') && code.endsWith(' ') && code.isNotBlank()) {
                            code = code.substring(1, code.length - 1)
                        }
                        out.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = style.codeBackground))
                        out.append(code)
                        out.pop()
                        i = close + run
                    }
                }
                (c == '[' || (c == '!' && text.getOrNull(i + 1) == '[')) -> {
                    val link = parseLink(text, if (c == '!') i + 1 else i)
                    if (link == null) {
                        plain.append(c)
                        i++
                    } else {
                        flush()
                        val label = link.label.ifBlank { link.url }
                        writeLink(link.url) { write(label) }
                        i = link.end
                    }
                }
                c == '<' -> {
                    val close = text.indexOf('>', i + 1)
                    val target = if (close > 0) text.substring(i + 1, close) else ""
                    if (close > 0 && OPENABLE_URL.matches(target)) {
                        flush()
                        writeLink(target) { out.append(target) }
                        i = close + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }
                (c == 'h' || c == 'H') && (i == 0 || !text[i - 1].isLetterOrDigit()) &&
                    (text.startsWith("http://", i, ignoreCase = true) || text.startsWith("https://", i, ignoreCase = true)) &&
                    BARE_URL.find(text, i)?.range?.first == i -> {
                    val url = trimUrl(BARE_URL.find(text, i)!!.value)
                    flush()
                    writeLink(url) { out.append(url) }
                    i += url.length
                }
                c == '*' || c == '_' || c == '~' -> {
                    val run = runLength(text, i, c)
                    val close = if ((c == '~' && run == 2) || (c != '~' && run <= 3)) findEmphasisClose(text, i, run, c) else -1
                    if (close < 0) {
                        plain.append(text, i, i + run)
                        i += run
                    } else {
                        flush()
                        out.pushStyle(
                            when {
                                c == '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                                run == 1 -> SpanStyle(fontStyle = FontStyle.Italic)
                                run == 2 -> SpanStyle(fontWeight = FontWeight.Bold)
                                else -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                            }
                        )
                        write(text.substring(i + run, close))
                        out.pop()
                        i = close + run
                    }
                }
                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flush()
    }

    private fun writeLink(url: String, label: () -> Unit) {
        val openable = OPENABLE_URL.matches(url)
        if (openable) out.pushStringAnnotation(MARKDOWN_URL_TAG, url)
        out.pushStyle(SpanStyle(color = style.link, textDecoration = if (openable) TextDecoration.Underline else null))
        label()
        out.pop()
        if (openable) out.pop()
    }
}

private fun runLength(text: String, start: Int, c: Char): Int {
    var end = start
    while (end < text.length && text[end] == c) end++
    return end - start
}

private fun findCodeClose(text: String, from: Int, run: Int): Int {
    var i = text.indexOf('`', from)
    while (i >= 0) {
        val length = runLength(text, i, '`')
        if (length == run) return i
        i = text.indexOf('`', i + length)
    }
    return -1
}

/** Finds a closing delimiter run of the same character and length, skipping code spans. */
private fun findEmphasisClose(text: String, open: Int, run: Int, c: Char): Int {
    val after = text.getOrNull(open + run) ?: return -1
    if (after.isWhitespace()) return -1
    // An underscore inside a word (snake_case) is not emphasis.
    if (c == '_' && open > 0 && text[open - 1].isLetterOrDigit()) return -1
    var i = open + run
    while (i < text.length) {
        val ch = text[i]
        when {
            ch == '\\' -> i += 2
            ch == '`' -> {
                val length = runLength(text, i, '`')
                val close = findCodeClose(text, i + length, length)
                i = if (close < 0) i + length else close + length
            }
            ch == c -> {
                val length = runLength(text, i, c)
                val before = text[i - 1]
                val next = text.getOrNull(i + length)
                if (length == run && i > open + run && !before.isWhitespace() &&
                    !(c == '_' && next != null && next.isLetterOrDigit())) return i
                i += length
            }
            else -> i++
        }
    }
    return -1
}

private class InlineLink(val label: String, val url: String, val end: Int)

private fun parseLink(text: String, open: Int): InlineLink? {
    var depth = 0
    var i = open
    var labelEnd = -1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i++
            '[' -> depth++
            ']' -> if (--depth == 0) {
                labelEnd = i
                break
            }
        }
        i++
    }
    if (labelEnd < 0 || text.getOrNull(labelEnd + 1) != '(') return null
    var parens = 0
    var j = labelEnd + 2
    while (j < text.length) {
        val c = text[j]
        if (c == '\\') j++
        else if (c == '(') parens++
        else if (c == ')') { if (parens == 0) break else parens-- }
        else if (c == '\n') return null
        j++
    }
    if (j >= text.length) return null
    // Drops an optional "title" and angle brackets around the destination.
    val destination = text.substring(labelEnd + 2, j).trim().substringBefore(' ').removeSurrounding("<", ">")
    return InlineLink(text.substring(open + 1, labelEnd), destination, j + 1)
}

private fun trimUrl(url: String): String {
    var end = url.length
    while (end > 0) {
        val last = url[end - 1]
        val trim = last in ".,;:!?'\"*_~" ||
            (last == ')' && url.substring(0, end).count { it == '(' } < url.substring(0, end).count { it == ')' })
        if (!trim) break
        end--
    }
    return url.substring(0, end)
}
