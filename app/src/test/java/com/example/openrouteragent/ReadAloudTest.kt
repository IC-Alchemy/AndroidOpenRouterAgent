package com.example.openrouteragent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudTest {
    @Test
    fun markdownIsReadAsPlainSentences() {
        val markdown = """
            # Plan

            Here is **bold**, *italic*, `code` and a [link](https://x.com/a). Also https://y.com/b and my_var_name.

            - one
            - two _three_

            > quoted

            ---

            | A | B |
            |---|:---:|
            | 1 | 2 |

            ```kotlin
            val x = 1
            ```
            ![chart](c.png) done.
        """.trimIndent()
        assertEquals("""
            Plan

            Here is bold, italic, code and a link. Also link and my_var_name.

            one
            two three

            quoted

            A, B

            1, 2

            (Code omitted.)

            chart. done.
        """.trimIndent(), speakableText(markdown))
    }

    @Test
    fun longRepliesSplitAtSentenceEnds() {
        val text = (1..40).joinToString(" ") { "Sentence number $it is here." }
        val chunks = speechChunks(text, 200)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 200 })
        assertTrue(chunks.dropLast(1).all { it.endsWith(".") })
        assertEquals(text, chunks.joinToString(" "))
        assertTrue(speechChunks("   ", 200).isEmpty())
    }

    @Test
    fun voiceRepliesAreReadByDefault() {
        assertTrue(SdkSettings().readVoiceReplies)
    }
}
