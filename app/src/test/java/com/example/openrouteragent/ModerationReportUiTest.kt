package com.example.openrouteragent

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModerationReportUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyAiResponseBubbleExposesReportButtonIncludingErrorAndModeratedCards() {
        val reported = mutableListOf<String>()
        val normalEntry = ChatEntry(id = "1", isUser = false, text = "Normal answer")
        val errorEntry = ChatEntry(id = "2", isUser = false, text = "HTTP 500 error", isError = true)
        val moderatedEntry = ChatEntry(
            id = "3",
            isUser = false,
            text = POLICY_RESPONSE_WARNING,
            isModerated = true,
        )

        composeRule.setContent {
            OpenRouterAgentTheme {
                androidx.compose.foundation.layout.Column {
                    AgentResponseCard(
                        entry = normalEntry,
                        onCopyText = {},
                        onFileClick = { _, _ -> },
                        onReport = { reported += normalEntry.id },
                    )
                    AgentResponseCard(
                        entry = errorEntry,
                        onCopyText = {},
                        onFileClick = { _, _ -> },
                        onReport = { reported += errorEntry.id },
                    )
                    AgentResponseCard(
                        entry = moderatedEntry,
                        onCopyText = {},
                        onFileClick = { _, _ -> },
                        onReport = { reported += moderatedEntry.id },
                    )
                }
            }
        }

        val reportButtons = composeRule.onAllNodesWithContentDescription("Report reply")
        reportButtons.assertCountEquals(3)
        // Copy reply only appears on the normal card, suppressed on error and moderated cards.
        composeRule.onAllNodesWithContentDescription("Copy reply").assertCountEquals(1)

        reportButtons[0].performClick()
        reportButtons[1].performClick()
        reportButtons[2].performClick()

        assertEquals(listOf("1", "2", "3"), reported)
    }

    @Test
    fun reportDialogSubmitsPairedPromptResponseAndSelectedReason() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"data":[{"id":"mock/model","name":"Mock","supported_parameters":["tools"]}]}""")
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("Questionable AI reply")
            )
            server.enqueue(MockResponse().setResponseCode(200))

            val app = ApplicationProvider.getApplicationContext<Application>()
            val vm = AgentViewModel(
                application = app,
                textModerator = object : TextModerator {
                    override suspend fun isSafe(text: String) = true
                },
                reportClient = ModerationReportClient(endpointUrl = server.url("/report").toString()),
                client = OpenRouterAgentClient(baseUrl = server.url("/v1/")),
                sdk = TestAgentSdkRuntime(app, server),
                storage = testAgentStorage(app),
            )
            val deadline = System.currentTimeMillis() + 5_000L
            while (vm.isLoading && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            runBlocking {
                vm.saveSettings(
                    AgentConfig(
                        apiKey = "test-openrouter-key",
                        model = "mock/model",
                    )
                )
            }
            while (vm.catalogLoading && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals("/v1/models/user", server.takeRequest().path)
            vm.send("Tell me a story")
            val chatDeadline = System.currentTimeMillis() + 5_000L
            while (vm.workspace.messages.size < 2 && System.currentTimeMillis() < chatDeadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals(2, vm.workspace.messages.size)
            assertEquals("/v1/responses", server.takeRequest().path)

            composeRule.setContent {
                OpenRouterAgentTheme {
                    OpenRouterAgentScreen(vm)
                }
            }

            composeRule.onNodeWithText("Questionable AI reply").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Report reply").performClick()

            composeRule.onNodeWithText("Report response").assertIsDisplayed()
            composeRule.onNodeWithText("Sexual content").assertIsDisplayed()
            composeRule.onNodeWithText("Harassment").assertIsDisplayed()
            composeRule.onNodeWithText("Other").assertIsDisplayed()

            composeRule.onNodeWithText("Harassment").performClick()
            composeRule.onNodeWithText("Submit").performClick()

            val reportRequest = server.takeRequest()
            assertEquals("/report", reportRequest.path)
            val json = JSONObject(reportRequest.body.readUtf8())
            assertEquals(setOf("prompt", "response", "reason"), json.keys().asSequence().toSet())
            assertEquals("Tell me a story", json.getString("prompt"))
            assertEquals("Questionable AI reply", json.getString("response"))
            assertEquals("Harassment", json.getString("reason"))
            assertTrue(vm.reportMessage == null || vm.reportMessage == "Report submitted. Thank you.")

            composeRule.onNodeWithContentDescription("Configure").performClick()
            composeRule.onNodeWithText("Settings").assertIsDisplayed()
            composeRule.onAllNodesWithText("Providers").assertCountEquals(0)
        }
    }

    @Test
    fun settingsModerationSwitchDefaultsOffAndTogglesOnAndOffOnSave() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AgentViewModel(
            application = app,
            textModerator = null,
            reportClient = ModerationReportClient(endpointUrl = ""),
        )
        val deadline = System.currentTimeMillis() + 5_000L
        while (vm.isLoading && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        var showSheet by mutableStateOf(true)
        var initial by mutableStateOf(AgentConfig())
        var savedConfig: AgentConfig? = null

        composeRule.setContent {
            OpenRouterAgentTheme {
                if (showSheet) {
                    SettingsBottomSheet(
                        initialConfig = initial,
                        onDismiss = { showSheet = false },
                        onSave = { updated -> savedConfig = updated },
                        onValidate = {},
                        agent = vm,
                    )
                }
            }
        }

        val toggleNode = composeRule.onNodeWithText("On-device content moderation")
        toggleNode.performScrollTo().assertIsDisplayed().assertIsOff()

        // Toggle ON and save
        toggleNode.performSemanticsAction(SemanticsActions.OnClick)
        toggleNode.assertIsOn()
        composeRule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)

        val saveDeadline1 = System.currentTimeMillis() + 5_000L
        while (savedConfig == null && System.currentTimeMillis() < saveDeadline1) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(true, savedConfig?.enableModeration)

        // Reopen with enableModeration = true, toggle OFF, and save
        savedConfig = null
        initial = AgentConfig(enableModeration = true)
        showSheet = true
        composeRule.waitForIdle()

        val toggleNodeAgain = composeRule.onNodeWithText("On-device content moderation")
        toggleNodeAgain.performScrollTo().assertIsDisplayed().assertIsOn()
        toggleNodeAgain.performSemanticsAction(SemanticsActions.OnClick)
        toggleNodeAgain.assertIsOff()
        composeRule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)

        val saveDeadline2 = System.currentTimeMillis() + 5_000L
        while (savedConfig == null && System.currentTimeMillis() < saveDeadline2) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse(savedConfig?.enableModeration ?: true)
    }
}
