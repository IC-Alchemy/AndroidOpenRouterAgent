package com.example.openrouteragent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h820dp")
class UsageExplorerUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun recordsCanDrillIntoAnEndpointWithTheSelectedMeasure() {
        val today = LocalDate.of(2025, 8, 26)
        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    UsageExplorer(
                        listOf(
                            UsageActivity(today.minusDays(2), "openai/gpt-4.1", "gpt-4.1-version",
                                "OpenAI", "example-endpoint", UsageTotals(cost = 0.02, requests = 3)),
                        ),
                        today,
                    )
                }
            }
        }

        composeRule.onNodeWithText("View this day’s records").performScrollTo().performClick()
        composeRule.onNodeWithText("Show all days").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Highest spend").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("openai/gpt-4.1").performScrollTo().performClick()
        composeRule.onNodeWithText("Version · gpt-4.1-version").performScrollTo().assertIsDisplayed()
    }
}
