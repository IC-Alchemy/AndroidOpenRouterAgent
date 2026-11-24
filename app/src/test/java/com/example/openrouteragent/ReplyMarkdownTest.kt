package com.example.openrouteragent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyMarkdownTest {
    @Test
    fun formatsHeadingsListsAndOpenCodeFencesInStreamedReplies() {
        val reply = renderReplyMarkdown("# Result\n\n3. **Done**\n   - Next\n\n```kt\nprintln(42)", Color.Blue, Color.White)

        assertEquals("Result\n\n3. Done\n  • Next\n\nprintln(42)", reply.text)
        assertTrue(reply.spanStyles.any { it.start == 0 && it.end == 6 && it.item.fontWeight == FontWeight.Bold })
        assertTrue(reply.spanStyles.any { it.end == reply.length && it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun makesWebLinksActionableAndKeepsOtherLinksAndHtmlInert() {
        val reply = renderReplyMarkdown(
            "[Source](https://example.com/path) [Other](file:///test) <img src=\"https://example.com/a\">",
            Color.Blue,
            Color.White,
        )

        assertEquals("Source Other <img src=\"https://example.com/a\">", reply.text)
        val link = reply.getStringAnnotations(REPLY_LINK, 0, reply.length).single()
        assertEquals("https://example.com/path", link.item)
        assertEquals("Source", reply.text.substring(link.start, link.end))
    }
}
