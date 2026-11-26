package com.example.openrouteragent

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h820dp")
class UsageExplorerUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun activityKeySurvivesReloadAndCanBeForgottenWithoutChangingWorkspaceKey() {
        val app = RuntimeEnvironment.getApplication()
        val config = AgentConfig(managementKey = "workspace-only-key", activityManagementKey = "activity-only-key")
        testAgentStorage(app).saveConfig(config)

        val reloaded = testAgentStorage(app).loadConfig()
        assertEquals("activity-only-key", reloaded.activityManagementKey)
        assertEquals("workspace-only-key", reloaded.managementKey)
        val prefs = app.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.any { "activity-only-key" in it.toString() })

        testAgentStorage(app).saveConfig(reloaded.copy(activityManagementKey = ""))
        val forgotten = testAgentStorage(app).loadConfig()
        assertEquals("", forgotten.activityManagementKey)
        assertEquals("workspace-only-key", forgotten.managementKey)
    }

    @Test fun activityRejectionShowsOpenRouterReasonAndRequestIdWithoutTheKey() {
        MockWebServer().use { server ->
            val key = "sk-or-v1-" + "b".repeat(64)
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .setHeader("request-id", "req_activity_123")
                    .setBody("""{"error":{"message":"API key expired: $key","code":401}}""")
            )
            val client = OpenRouterAgentClient(server.url("/api/v1/"))
            composeRule.setContent {
                OpenRouterAgentTheme(darkTheme = true) {
                    UsageScreen(
                        client, apiKey = "", savedManagementKey = "workspace-key", activityManagementKey = key,
                        onBack = {}, onConnect = {}, onSaveActivityKey = {},
                    )
                }
            }

            val detail = composeRule.onNodeWithText("API key expired: [API key]", substring = true)
            composeRule.waitUntil(5_000) { runCatching { detail.fetchSemanticsNode() }.isSuccess }
            detail.performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("req_activity_123", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText(key, substring = true).assertDoesNotExist()
            composeRule.onNodeWithText("Activity access").assertDoesNotExist()
            assertEquals("Bearer $key", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun unreadableActivityResponseShowsItsRequestId() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("request-id", "req_unreadable_activity")
                    .setBody("""{"data":{"unexpected":"shape"}}""")
            )
            val client = OpenRouterAgentClient(server.url("/api/v1/"))
            composeRule.setContent {
                OpenRouterAgentTheme(darkTheme = true) {
                    UsageScreen(
                        client, apiKey = "", savedManagementKey = "management-key",
                        onBack = {}, onConnect = {}, onSaveActivityKey = {},
                    )
                }
            }

            val detail = composeRule.onNodeWithText("req_unreadable_activity", substring = true)
            composeRule.waitUntil(5_000) { runCatching { detail.fetchSemanticsNode() }.isSuccess }
            detail.performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("OpenRouter returned activity the app couldn’t read.").assertIsDisplayed()
        }
    }

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

        composeRule.onNodeWithText("Activity totals").assertIsDisplayed()
        composeRule.onNodeWithText("View this day’s records").performScrollTo().performClick()
        composeRule.onNodeWithText("Show all days").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Highest spend").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("openai/gpt-4.1").performScrollTo().performClick()
        composeRule.onNodeWithText("Version · gpt-4.1-version").performScrollTo().assertIsDisplayed()
    }
}
