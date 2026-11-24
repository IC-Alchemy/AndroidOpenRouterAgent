package com.example.openrouteragent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private class MarkdownScope(
    val style: TextStyle,
    val color: Color,
    val inline: MarkdownInlineStyle,
    val keepSelection: Boolean,
    val openLink: (String) -> Unit,
    val depth: Int = 0,
) {
    fun nested(color: Color = this.color) = MarkdownScope(style, color, inline, keepSelection, openLink, depth + 1)
}

/**
 * Renders an agent reply written in Markdown. Text stays selectable and copyable block by block,
 * code blocks scroll sideways and copy whole, and links to web, mail and phone addresses open on tap.
 */
@Composable
internal fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    keepSelectionOnReplacement: Boolean = false,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val inline = remember(colors.primary, colors.onSurface) {
        MarkdownInlineStyle(link = colors.primary, codeBackground = colors.onSurface.copy(alpha = 0.10f))
    }
    val scope = MarkdownScope(
        style = style,
        color = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } },
        inline = inline,
        keepSelection = keepSelectionOnReplacement,
        openLink = { url -> runCatching { uriHandler.openUri(url) } },
    )
    MarkdownBlocks(blocks, scope, modifier.fillMaxWidth())
}

@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>, md: MarkdownScope, modifier: Modifier = Modifier, gap: Dp = 8.dp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        blocks.forEach { MarkdownBlock(it, md) }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, md: MarkdownScope) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    when (block) {
        is MdBlock.Paragraph -> InlineText(block.text, md)
        is MdBlock.Heading -> InlineText(
            block.text, md,
            style = when (block.level) {
                1 -> typography.titleLarge
                2 -> typography.titleMedium
                3 -> typography.titleSmall
                else -> md.style
            }.copy(fontWeight = if (block.level <= 3) FontWeight.SemiBold else FontWeight.Bold),
            modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 0.dp),
        )
        is MdBlock.Code -> CodeBlock(block, md)
        is MdBlock.Quote -> {
            val bar = colors.outlineVariant
            MarkdownBlocks(
                block.blocks, md.nested(color = colors.onSurfaceVariant),
                Modifier.fillMaxWidth().drawBehind {
                    drawRect(bar, size = Size(3.dp.toPx(), size.height))
                }.padding(start = 12.dp),
            )
        }
        is MdBlock.ListBlock -> MarkdownList(block, md)
        is MdBlock.Table -> MarkdownTable(block, md)
        MdBlock.Rule -> HorizontalDivider(
            Modifier.padding(vertical = 4.dp), thickness = StudioHairlineWidth, color = colors.outlineVariant,
        )
    }
}

@Composable
private fun InlineText(text: String, md: MarkdownScope, modifier: Modifier = Modifier, style: TextStyle = md.style) {
    val annotated = remember(text, md.inline) { markdownInline(text, md.inline) }
    CopyableText(annotated, modifier, style = style, color = md.color,
        keepSelectionOnReplacement = md.keepSelection, onLinkClick = md.openLink)
}

@Composable
private fun CodeBlock(block: MdBlock.Code, md: MarkdownScope) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalSelectionClipboard.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceContainerHighest,
        border = BorderStroke(StudioHairlineWidth, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(block.language.ifBlank { "code" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
                SymbolAction("Copy code", StudioSymbols.Copy, onClick = { clipboard.copy(block.code) })
            }
            // Long lines scroll sideways instead of wrapping, so the code keeps its shape.
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                CopyableText(
                    AnnotatedString(block.code),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurface,
                    keepSelectionOnReplacement = md.keepSelection,
                )
            }
        }
    }
}

@Composable
private fun MarkdownList(block: MdBlock.ListBlock, md: MarkdownScope) {
    val bullet = when (md.depth % 3) {
        0 -> "•"
        1 -> "◦"
        else -> "▪"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth()) {
                val marker = when {
                    item.checked == true -> "☑"
                    item.checked == false -> "☐"
                    block.ordered -> "${block.start + index}."
                    else -> bullet
                }
                Text(
                    marker,
                    Modifier.widthIn(min = if (block.ordered) 24.dp else 18.dp).padding(end = 6.dp),
                    style = md.style,
                    color = if (block.ordered || item.checked != null) md.color else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (block.ordered) TextAlign.End else TextAlign.Center,
                )
                MarkdownBlocks(item.blocks, md.nested(), Modifier.weight(1f), gap = 4.dp)
            }
        }
    }
}

private class TableGrid(val columns: IntArray, val rows: IntArray)

@Composable
private fun MarkdownTable(table: MdBlock.Table, md: MarkdownScope) {
    val colors = MaterialTheme.colorScheme
    val line = colors.outlineVariant
    val headerFill = colors.surfaceContainerHigh
    var grid by remember { mutableStateOf(TableGrid(IntArray(0), IntArray(0))) }
    val columns = table.header.size
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fillWidth = constraints.maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            SelectionContainer {
                Layout(
                    content = {
                        (listOf(table.header) + table.rows).forEachIndexed { row, cells ->
                            cells.forEachIndexed { column, cell ->
                                TableCell(cell, row == 0, table.alignments.getOrElse(column) { MdAlign.Start }, md)
                            }
                        }
                    },
                    modifier = Modifier
                        .border(StudioHairlineWidth, line, RoundedCornerShape(6.dp))
                        .drawBehind {
                            val stroke = StudioHairlineWidth.toPx()
                            grid.rows.firstOrNull()?.let { drawRect(headerFill, size = Size(size.width, it.toFloat())) }
                            var y = 0f
                            grid.rows.dropLast(1).forEach {
                                y += it
                                drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
                            }
                            var x = 0f
                            grid.columns.dropLast(1).forEach {
                                x += it
                                drawLine(line, Offset(x, 0f), Offset(x, size.height), stroke)
                            }
                        },
                ) { measurables, _ ->
                    val rowCount = measurables.size / columns
                    val widest = 280.dp.roundToPx()
                    val narrowest = 48.dp.roundToPx()
                    val widths = IntArray(columns) { column ->
                        (0 until rowCount).maxOf { row ->
                            measurables[row * columns + column].maxIntrinsicWidth(Constraints.Infinity)
                        }.coerceIn(narrowest, widest)
                    }
                    // A narrow table stretches to the reply's width; a wide one scrolls.
                    val spare = fillWidth - widths.sum()
                    if (fillWidth != Constraints.Infinity && spare > 0) {
                        val total = widths.sum()
                        val shares = IntArray(columns) { spare * widths[it] / total }
                        shares[columns - 1] += spare - shares.sum()
                        for (column in 0 until columns) widths[column] += shares[column]
                    }
                    val placeables = measurables.mapIndexed { index, measurable ->
                        measurable.measure(Constraints.fixedWidth(widths[index % columns]))
                    }
                    val heights = IntArray(rowCount) { row -> (0 until columns).maxOf { placeables[row * columns + it].height } }
                    // Measuring must not subscribe to the grid it publishes for drawing.
                    val drawn = Snapshot.withoutReadObservation { grid }
                    if (!drawn.columns.contentEquals(widths) || !drawn.rows.contentEquals(heights)) {
                        grid = TableGrid(widths, heights)
                    }
                    layout(widths.sum(), heights.sum()) {
                        var y = 0
                        for (row in 0 until rowCount) {
                            var x = 0
                            for (column in 0 until columns) {
                                placeables[row * columns + column].place(x, y)
                                x += widths[column]
                            }
                            y += heights[row]
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, header: Boolean, align: MdAlign, md: MarkdownScope) {
    val annotated = remember(text, md.inline) { markdownInline(text, md.inline) }
    val current by rememberUpdatedState(annotated)
    val openLink by rememberUpdatedState(md.openLink)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        annotated,
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            .linkTaps({ layout?.let { it to current } }) { openLink(it) },
        style = md.style.copy(
            fontWeight = if (header) FontWeight.SemiBold else md.style.fontWeight,
            textAlign = when (align) {
                MdAlign.Start -> TextAlign.Start
                MdAlign.Center -> TextAlign.Center
                MdAlign.End -> TextAlign.End
            },
        ),
        color = md.color,
        onTextLayout = { layout = it },
    )
}
