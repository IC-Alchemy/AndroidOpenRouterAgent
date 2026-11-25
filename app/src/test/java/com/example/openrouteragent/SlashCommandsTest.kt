package com.example.openrouteragent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandsTest {
    @Test
    fun sideAndBtwAloneOpenTheSideConversation() {
        for (input in listOf("/side", "/btw", "  /BTW  ", "/Side\n")) {
            val parsed = SlashCommands.parse(input)
            assertEquals(input, SlashCommands.Side, parsed?.command)
            assertEquals(input, "", parsed?.argument)
        }
    }

    @Test
    fun textAfterTheCommandIsTheSideMessage() {
        assertEquals("what does this error mean?", SlashCommands.parse("/btw what does this error mean?")?.argument)
        assertEquals("line one\nline two", SlashCommands.parse("/side\tline one\nline two  ")?.argument)
    }

    @Test
    fun otherTextIsNotACommand() {
        for (input in listOf("btw what is this", "/sideways", "/btwx", "/", "/ btw", "/help", "a /btw b", "")) {
            assertNull(input, SlashCommands.parse(input))
        }
    }

    @Test
    fun everyCommandNameIsUnique() {
        val names = SlashCommands.all.flatMap { it.names }
        assertEquals(names.size, names.toSet().size)
    }
}
