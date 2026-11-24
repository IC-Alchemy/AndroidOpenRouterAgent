package com.example.openrouteragent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

internal const val REPLY_LINK = "replyLink"

private val replyMarkdownParser = Parser.builder().extensions(listOf(
    StrikethroughExtension.create(),
    TablesExtension.builder().maxCells(2_000).build(),
)).build()

@Composable
internal fun ReplyText(
    text: String,
    renderMarkdown: Boolean,
    color: Color = Color.Unspecified,
    keepSelectionOnReplacement: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val displayed = remember(text, renderMarkdown, colors.primary, colors.surfaceContainerHighest) {
        if (renderMarkdown) renderReplyMarkdown(text, colors.primary, colors.surfaceContainerHighest)
        else AnnotatedString(text)
    }
    CopyableText(displayed, style = MaterialTheme.typography.bodyMedium, color = color,
        keepSelectionOnReplacement = keepSelectionOnReplacement)
}

internal fun renderReplyMarkdown(source: String, linkColor: Color, codeBackground: Color): AnnotatedString {
    val document = try {
        replyMarkdownParser.parse(source)
    } catch (_: RuntimeException) {
        // Complex or incomplete streamed input can still be displayed and copied as source.
        return AnnotatedString(source)
    }
    return buildAnnotatedString { ReplyMarkdownRenderer(this, linkColor, codeBackground).render(document) }
        .takeIf { it.isNotEmpty() } ?: AnnotatedString(source)
}

private class ReplyMarkdownRenderer(
    private val text: AnnotatedString.Builder,
    linkColor: Color,
    codeBackground: Color,
) {
    private val bold = SpanStyle(fontWeight = FontWeight.Bold)
    private val italic = SpanStyle(fontStyle = FontStyle.Italic)
    private val code = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
    private val link = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)

    fun render(node: Node, depth: Int = 0) {
        when (node) {
            is Document -> children(node, depth, "\n\n")
            is Text -> text.append(node.literal)
            is Heading -> text.withStyle(bold.copy(fontSize = headingSizes[node.level - 1].sp)) { children(node, depth) }
            is StrongEmphasis -> text.withStyle(bold) { children(node, depth) }
            is Emphasis -> text.withStyle(italic) { children(node, depth) }
            is Strikethrough -> text.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children(node, depth) }
            is Code -> text.withStyle(code) { append(node.literal) }
            is FencedCodeBlock -> codeBlock(node.literal)
            is IndentedCodeBlock -> codeBlock(node.literal)
            is Link -> linkText(node, depth)
            is BlockQuote -> {
                text.append("│ ")
                text.withStyle(italic) { children(node, depth, "\n│ ") }
            }
            is OrderedList -> list(node, depth, node.markerStartNumber ?: 1)
            is BulletList -> list(node, depth, null)
            is TableBlock -> node.children().flatMap { it.children() }.forEachIndexed { index, row ->
                if (index > 0) text.append('\n')
                render(row, depth)
            }
            is TableRow -> children(node, depth, "  │  ")
            is TableCell -> if (node.isHeader) text.withStyle(bold) { children(node, depth) } else children(node, depth)
            is SoftLineBreak, is HardLineBreak -> text.append('\n')
            is ThematicBreak -> text.append("────────")
            is HtmlBlock -> text.append(node.literal.trimEnd('\n'))
            is HtmlInline -> text.append(node.literal)
            else -> children(node, depth)
        }
    }

    private fun codeBlock(literal: String) {
        text.withStyle(code) { append(literal.removeSuffix("\n")) }
    }

    private fun linkText(node: Link, depth: Int) {
        val url = node.destination.toHttpUrlOrNull()
        if (url == null) {
            children(node, depth)
            return
        }
        val start = text.length
        text.withStyle(link) { children(node, depth) }
        if (start < text.length) text.addStringAnnotation(REPLY_LINK, url.toString(), start, text.length)
    }

    private fun list(node: Node, depth: Int, start: Int?) {
        node.children().forEachIndexed { index, item ->
            if (index > 0) text.append('\n')
            text.append("  ".repeat(depth))
            text.append(if (start == null) "• " else "${start + index}. ")
            children(item, depth + 1, "\n")
        }
    }

    private fun children(node: Node, depth: Int, separator: String = "") {
        node.children().forEachIndexed { index, child ->
            if (index > 0) text.append(separator)
            render(child, depth)
        }
    }

    private fun Node.children() = generateSequence(firstChild) { it.next }
}

private val headingSizes = intArrayOf(23, 21, 19, 17, 16, 15)
