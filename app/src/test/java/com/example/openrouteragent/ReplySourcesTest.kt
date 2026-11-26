package com.example.openrouteragent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ReplySourcesSummaryTest {
    @Test
    fun summaryCountsSourcesAndSearches() {
        assertEquals("5 sources · 1 search", sourcesSummary(5, 1))
        assertEquals("1 source", sourcesSummary(1, 0))
        assertEquals("2 searches", sourcesSummary(0, 2))
        assertEquals("Web search", sourcesSummary(0, 0))
    }

    @Test
    fun hostDropsWwwAndIgnoresAddressesWithoutOne() {
        assertEquals("openrouter.ai", sourceHost("https://www.openrouter.ai/docs/body-builder"))
        assertEquals("oke.omqkhafi.dev", sourceHost("https://oke.omqkhafi.dev/docs/recipes/openrouter"))
        assertNull(sourceHost("not a url"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplySourcesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sourcesStayFoldedUntilTapped() {
        composeRule.setContent {
            OpenRouterAgentTheme {
                AgentResponseCard(
                    entry = ChatEntry(
                        id = "r1", isUser = false, text = "Body Builder sends one request per model.",
                        searchQueries = listOf("OpenRouter Body Builder compare models"),
                        citations = listOf(
                            WebCitation("Body Builder - Generate Parallel API Requests", "https://openrouter.ai/bb"),
                            WebCitation("Body Builder (beta) - API Pricing", "https://www.openrouter.ai/pricing"),
                        ),
                    ),
                    onCopyText = {},
                    onFileClick = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Body Builder sends one request per model.").assertIsDisplayed()
        composeRule.onNodeWithText("Body Builder - Generate Parallel API Requests").assertDoesNotExist()
        composeRule.onNodeWithText("Searched: OpenRouter Body Builder compare models").assertDoesNotExist()

        composeRule.onNodeWithText("2 sources · 1 search").performClick()
        composeRule.onNodeWithText("Searched: OpenRouter Body Builder compare models").assertIsDisplayed()
        composeRule.onNodeWithText("Body Builder - Generate Parallel API Requests").assertIsDisplayed()
        composeRule.onNodeWithText("Body Builder (beta) - API Pricing").assertIsDisplayed()

        composeRule.onNodeWithText("2 sources · 1 search").performClick()
        composeRule.onNodeWithText("Body Builder - Generate Parallel API Requests").assertDoesNotExist()
    }
}
