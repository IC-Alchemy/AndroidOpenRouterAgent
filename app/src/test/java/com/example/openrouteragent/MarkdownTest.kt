package com.example.openrouteragent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private val style = MarkdownInlineStyle(link = Color.Blue, codeBackground = Color.Gray)

    private fun inline(text: String) = markdownInline(text, style)

    private fun AnnotatedString.styledText(predicate: (androidx.compose.ui.text.SpanStyle) -> Boolean) =
        spanStyles.filter { predicate(it.item) }.map { substring(it.start, it.end) }

    private fun AnnotatedString.urls() = getStringAnnotations(MARKDOWN_URL_TAG, 0, length).map { substring(it.start, it.end) to it.item }

    @Test
    fun headingsParagraphsAndRules() {
        val blocks = parseMarkdown("# Title #\n\nFirst line\nsecond line\n\n---\n### Small")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Paragraph("First line\nsecond line"),
                MdBlock.Rule,
                MdBlock.Heading(3, "Small"),
            ),
            blocks,
        )
        assertEquals(listOf(MdBlock.Paragraph("#hashtag is not a heading")), parseMarkdown("#hashtag is not a heading"))
    }

    @Test
    fun fencedCodeKeepsContentAndToleratesAnUnclosedFenceWhileStreaming() {
        val blocks = parseMarkdown("Run:\n```kotlin\nval x = 1\n\n  *not emphasis*\n```\nDone")
        assertEquals(
            listOf(
                MdBlock.Paragraph("Run:"),
                MdBlock.Code("kotlin", "val x = 1\n\n  *not emphasis*"),
                MdBlock.Paragraph("Done"),
            ),
            blocks,
        )
        assertEquals(listOf(MdBlock.Code("", "partial\nline")), parseMarkdown("~~~\npartial\nline"))
    }

    @Test
    fun nestedOrderedAndTaskLists() {
        val blocks = parseMarkdown(
            """
            Steps:
            1. Install
               - with npm
               - or yarn
            2. Run

               Continued paragraph.
            3. Third

            - [x] done
            - [ ] todo
            """.trimIndent()
        )
        assertEquals(MdBlock.Paragraph("Steps:"), blocks[0])
        val steps = blocks[1] as MdBlock.ListBlock
        assertTrue(steps.ordered)
        assertEquals(1, steps.start)
        assertEquals(3, steps.items.size)
        assertEquals(
            listOf(
                MdBlock.Paragraph("Install"),
                MdBlock.ListBlock(false, 0, listOf(
                    MdListItem(listOf(MdBlock.Paragraph("with npm"))),
                    MdListItem(listOf(MdBlock.Paragraph("or yarn"))),
                )),
            ),
            steps.items[0].blocks,
        )
        assertEquals(listOf(MdBlock.Paragraph("Run"), MdBlock.Paragraph("Continued paragraph.")), steps.items[1].blocks)
        val tasks = blocks[2] as MdBlock.ListBlock
        assertEquals(listOf(true, false), tasks.items.map { it.checked })
        assertEquals(MdBlock.Paragraph("done"), tasks.items[0].blocks.single())
    }

    @Test
    fun listsWithTwoSpaceNestingUnderNumbersAndCodeInItems() {
        val blocks = parseMarkdown("1. First\n  - nested\n2. Second\n   ```sh\n   ls\n\n   pwd\n   ```")
        val list = blocks.single() as MdBlock.ListBlock
        assertEquals(2, list.items.size)
        assertTrue(list.items[0].blocks[1] is MdBlock.ListBlock)
        assertEquals(MdBlock.Code("sh", "ls\n\npwd"), list.items[1].blocks[1])
    }

    @Test
    fun quotesNestParsedBlocks() {
        val blocks = parseMarkdown("> **Note**\n> - one\n> - two\nlazy line\n\nAfter")
        val quote = blocks[0] as MdBlock.Quote
        assertEquals(MdBlock.Paragraph("**Note**"), quote.blocks[0])
        assertEquals(2, (quote.blocks[1] as MdBlock.ListBlock).items.size)
        assertEquals(MdBlock.Paragraph("After"), blocks[1])
    }

    @Test
    fun tablesReadAlignmentsEscapedPipesAndPadRaggedRows() {
        val blocks = parseMarkdown(
            "| Name | Cost | Note |\n|:-----|-----:|:----:|\n| a | 1 | `x|y` |\n| b \\| c | 2 |\n\nText"
        )
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("Name", "Cost", "Note"), table.header)
        assertEquals(listOf(MdAlign.Start, MdAlign.End, MdAlign.Center), table.alignments)
        assertEquals(listOf(listOf("a", "1", "`x|y`"), listOf("b | c", "2", "")), table.rows)
        assertEquals(MdBlock.Paragraph("Text"), blocks[1])
        // A pipe in prose without a divider row stays a paragraph.
        assertEquals(listOf(MdBlock.Paragraph("a | b")), parseMarkdown("a | b"))
    }

    @Test
    fun inlineEmphasisCodeAndStrikethrough() {
        val text = inline("Use **bold**, *italic*, ***both***, ~~gone~~ and `a*b*c` in snake_case_name.")
        assertEquals("Use bold, italic, both, gone and a*b*c in snake_case_name.", text.text)
        assertEquals(listOf("bold", "both"), text.styledText { it.fontWeight == FontWeight.Bold })
        assertEquals(listOf("italic", "both"), text.styledText { it.fontStyle == FontStyle.Italic })
        assertEquals(listOf("gone"), text.styledText { it.textDecoration == TextDecoration.LineThrough })
        assertEquals(listOf("a*b*c"), text.styledText { it.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun unmatchedOrSpacedMarkersStayLiteral() {
        assertEquals("2 * 3 * 4 and **open", inline("2 * 3 * 4 and **open").text)
        assertEquals("escaped *stars*", inline("escaped \\*stars\\*").text)
        assertTrue(inline("2 * 3 * 4").spanStyles.isEmpty())
    }

    @Test
    fun linksAutolinksAndBareUrlsCarryOpenableTargets() {
        val text = inline("See [the docs](https://example.com/a_(b) \"Title\"), <mailto:hi@example.com> or https://example.org/x.")
        assertEquals("See the docs, mailto:hi@example.com or https://example.org/x.", text.text)
        assertEquals(
            listOf(
                "the docs" to "https://example.com/a_(b)",
                "mailto:hi@example.com" to "mailto:hi@example.com",
                "https://example.org/x" to "https://example.org/x",
            ),
            text.urls(),
        )
        // Only web, mail and phone links open; others render as styled text without a target.
        val script = inline("[click](javascript:alert(1))")
        assertEquals("click", script.text)
        assertTrue(script.urls().isEmpty())
    }

    @Test
    fun htmlLineBreaksBecomeNewlines() {
        assertEquals("one\ntwo", inline("one<br>two").text)
        assertNull(parseMarkdown("").firstOrNull())
    }
}
