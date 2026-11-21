package com.example.openrouteragent

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionCopyTest {
    @Test
    fun `copies the exact selection in either direction`() {
        val before = TextFieldValue("A β\n emoji 🙂 Z")
        for (range in listOf(TextRange(2, 13), TextRange(13, 2))) {
            assertEquals("β\n emoji 🙂", selectedTextForCopy(before, before.copy(selection = range)))
        }
    }

    @Test
    fun `only a changed nonempty selection triggers a copy`() {
        val selected = TextFieldValue("one two", selection = TextRange(0, 3))
        assertNull(selectedTextForCopy(selected, selected))
        assertNull(selectedTextForCopy(selected, selected.copy(selection = TextRange(3))))
        assertNull(selectedTextForCopy(selected, selected.copy(text = "one three", selection = TextRange(4, 9))))
        assertEquals("two", selectedTextForCopy(selected, selected.copy(selection = TextRange(4, 7))))
    }

    @Test
    fun `only the latest adjusted range reaches the clipboard`() = runBlocking {
        val copies = mutableListOf<String>()
        val clipboard = SelectionClipboard(this) { copies.add(it) }
        clipboard.select("Copper")
        clipboard.select("Copper lanterns")
        delay(350)
        assertEquals(listOf("Copper lanterns"), copies)
    }

    @Test
    fun `explicit copy takes precedence over a pending selection`() = runBlocking {
        val copies = mutableListOf<String>()
        val clipboard = SelectionClipboard(this) { copies.add(it) }
        clipboard.select("Copper")
        clipboard.copy("Copper lanterns shimmer.")
        delay(350)
        assertEquals(listOf("Copper lanterns shimmer."), copies)
    }
}
