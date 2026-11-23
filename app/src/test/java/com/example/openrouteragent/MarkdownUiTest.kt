package com.example.openrouteragent

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkdownUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val reply = """
        # Plan

        Use **bold** and `code`.

        - first
        - second

        | Key | Value |
        |-----|------:|
        | a   | 1     |

        ```kotlin
        val answer = 42
        ```
    """.trimIndent()

    @Test
    fun responseCardRendersMarkdownByDefaultAndRawTextWhenToggledOff() {
        var render by mutableStateOf(true)
        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = true) {
                AgentResponseCard(
                    entry = ChatEntry(id = "r1", isUser = false, text = reply),
                    onCopyText = {},
                    onFileClick = { _, _ -> },
                    renderMarkdown = render,
                )
            }
        }

        composeRule.onNodeWithText("Plan").assertIsDisplayed()
        composeRule.onNodeWithText("Use bold and code.").assertIsDisplayed()
        composeRule.onNodeWithText("first").assertIsDisplayed()
        composeRule.onAllNodesWithText("•").assertCountEquals(2)
        composeRule.onNodeWithText("Value").assertIsDisplayed()
        composeRule.onNodeWithText("kotlin").assertIsDisplayed()
        composeRule.onNodeWithText("val answer = 42").assertIsDisplayed()

        composeRule.onNodeWithText("Copy code").performClick()
        composeRule.waitForIdle()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertEquals("val answer = 42", clipboard.primaryClip?.getItemAt(0)?.text)

        render = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText(reply).assertIsDisplayed()
        composeRule.onNodeWithText("Copy code").assertDoesNotExist()
    }

    @Test
    fun liveReplyRendersMarkdownAndHonorsTheToggle() {
        val progress = AgentProgress(started = 1L, lastEvent = 2L, timeline = listOf(LiveBlock.Answer("Streaming **now**")))
        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = true) {
                Column {
                    LiveAgentReply(progress, renderMarkdown = true)
                    LiveAgentReply(progress, renderMarkdown = false)
                }
            }
        }
        composeRule.onNodeWithText("Streaming now").assertIsDisplayed()
        composeRule.onNodeWithText("Streaming **now**").assertIsDisplayed()
    }

    // Link hit-testing needs real glyph positions, which legacy graphics fakes.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tappingALinkOpensItsUrl() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = true) {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    MarkdownText("[OpenRouter documentation](https://openrouter.ai/docs)")
                }
            }
        }
        composeRule.onNodeWithText("OpenRouter documentation").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("https://openrouter.ai/docs"), opened)
    }
}
