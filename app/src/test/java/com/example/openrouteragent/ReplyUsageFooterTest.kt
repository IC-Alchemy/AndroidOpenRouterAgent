package com.example.openrouteragent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ReplyUsageSummaryTest {
    private val usage = TokenUsage(
        inputTokens = 9039,
        outputTokens = 630,
        totalTokens = 9669,
        cost = 0.0004,
        reasoningTokens = 412,
    )

    @Test
    fun summaryIsJustTheCost() {
        assertEquals("$0.0004", replyUsageSummary(usage))
    }

    @Test
    fun summaryFallsBackToTokenTotalWithoutCost() {
        assertEquals("9669 tokens", replyUsageSummary(usage.copy(cost = null)))
    }

    @Test
    fun summaryIsDashWithoutUsage() {
        assertEquals("–", replyUsageSummary(null))
    }

    @Test
    fun detailsListModelTokensReasoningAndCost() {
        assertEquals(
            listOf(
                "Model: tencent/hy4-flash",
                "Tokens: 9669 total · 9039 in / 630 out",
                "Reasoning: 412 tokens",
                "Cost: $0.0004",
            ),
            replyUsageDetails("tencent/hy4-flash", usage),
        )
    }

    @Test
    fun detailsIncludeCachedAndSkipBlankModel() {
        val details = replyUsageDetails(" ", usage.copy(cachedTokens = 8000, reasoningTokens = 0, cost = null))
        assertEquals(
            listOf("Tokens: 9669 total · 9039 in / 630 out", "Cached: 8000 tokens"),
            details,
        )
    }

    @Test
    fun detailsAreEmptyWithoutModelOrUsage() {
        assertTrue(replyUsageDetails(null, null).isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplyUsageFooterTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(entry: ChatEntry) {
        composeRule.setContent {
            OpenRouterAgentTheme {
                AgentResponseCard(entry = entry, onCopyText = {}, onFileClick = { _, _ -> })
            }
        }
    }

    @Test
    fun footerShowsOnlyCostUntilTapped() {
        show(
            ChatEntry(
                id = "r1", isUser = false, text = "Answer",
                modelUsed = "tencent/hy4-flash",
                usage = TokenUsage(9039, 630, 9669, cost = 0.0004, reasoningTokens = 412),
            )
        )

        composeRule.onNodeWithText("$0.0004").assertIsDisplayed()
        composeRule.onNodeWithText("Model: tencent/hy4-flash").assertDoesNotExist()
        composeRule.onNodeWithText("Reasoning: 412 tokens").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Reply cost").performClick()
        composeRule.onNodeWithText("Model: tencent/hy4-flash").assertIsDisplayed()
        composeRule.onNodeWithText("Tokens: 9669 total · 9039 in / 630 out").assertIsDisplayed()
        composeRule.onNodeWithText("Reasoning: 412 tokens").assertIsDisplayed()
        composeRule.onNodeWithText("Cost: $0.0004").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Reply cost").performClick()
        composeRule.onNodeWithText("Model: tencent/hy4-flash").assertDoesNotExist()
    }

    @Test
    fun footerShowsTokenTotalWhenCostIsMissing() {
        show(
            ChatEntry(
                id = "r2", isUser = false, text = "Answer",
                usage = TokenUsage(100, 20, 120),
            )
        )
        composeRule.onNodeWithText("120 tokens").assertIsDisplayed()
    }

    @Test
    fun footerHasNoUsageLineWithoutModelOrUsage() {
        show(ChatEntry(id = "r3", isUser = false, text = "Answer"))
        composeRule.onNodeWithContentDescription("Reply cost").assertDoesNotExist()
    }
}
