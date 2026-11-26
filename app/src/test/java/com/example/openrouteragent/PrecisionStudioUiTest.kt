package com.example.openrouteragent

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrecisionStudioUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun studioThemeAppliesDarkAndLightPalettesTypographyAndShapes() {
        var darkPrimary = Color.Unspecified
        var darkBackground = Color.Unspecified
        var lightPrimary = Color.Unspecified
        var lightBackground = Color.Unspecified
        var headlineTracking = TextUnit.Unspecified
        var mediumShape = RoundedCornerShape(0.dp)

        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = true) {
                darkPrimary = MaterialTheme.colorScheme.primary
                darkBackground = MaterialTheme.colorScheme.background
                headlineTracking = MaterialTheme.typography.headlineSmall.letterSpacing
                mediumShape = MaterialTheme.shapes.medium as RoundedCornerShape
            }
            OpenRouterAgentTheme(darkTheme = false) {
                lightPrimary = MaterialTheme.colorScheme.primary
                lightBackground = MaterialTheme.colorScheme.background
            }
        }

        assertEquals(Color(0xFF38BDF8), darkPrimary)
        assertEquals(Color(0xFF050608), darkBackground)
        assertEquals(Color(0xFF0284C7), lightPrimary)
        assertEquals(Color(0xFFF7F8FA), lightBackground)
        assertEquals((-0.4).sp, headlineTracking)
        assertEquals(RoundedCornerShape(14.dp), mediumShape)
        assertEquals(0.75.dp, StudioHairlineWidth)
    }

    @Test
    fun outlinedSectionAndStudioRailRenderTitledUntitledAndHighlightedStates() {
        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Column {
                    OutlinedSection(title = "Connection", highlighted = true) {
                        Text("Section body")
                    }
                    OutlinedSection(title = null, highlighted = false) {
                        Text("Untitled body")
                    }
                    OutlinedSection(title = "   ", highlighted = false) {
                        Text("Blank title body")
                    }
                    StudioRailBlock {
                        Text("Rail content")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Connection").assertIsDisplayed()
        composeRule.onNodeWithText("Section body").assertIsDisplayed()
        composeRule.onNodeWithText("Untitled body").assertIsDisplayed()
        composeRule.onNodeWithText("Blank title body").assertIsDisplayed()
        composeRule.onNodeWithText("Rail content").assertIsDisplayed()
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertCountEquals(1)
    }

    @Test
    fun statusCapabilitiesBarAndEmptyWorkbenchRenderOpenRouterTools() {
        var selectedPrompt = ""
        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Column {
                    StatusCapabilitiesBar(
                        config = AgentConfig(
                            apiKey = "or-key",
                            enableShellSandbox = true,
                            enableWebSearch = true,
                            allowSandboxNetwork = true,
                        ),
                        activeContainerId = null,
                        canDetach = true,
                        inProject = true,
                        attachmentCount = 2,
                        onOpenProjectFiles = {},
                        onOpenWorkspaceFiles = {},
                        onClearAttachments = {},
                        onOpenFiles = {},
                        onResetContainer = {},
                    )
                    EmptyAgentState(
                        hasApiKey = false,
                        onOpenSettings = {},
                        onSelectPrompt = { selectedPrompt = it },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Project files").assertIsDisplayed()
        composeRule.onNodeWithText("Workspace files · 2 attached").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Hosted shell").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Web Search").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Egress Allowlist").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Connect OpenRouter").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Search the web for the latest OpenRouter server tools updates and summarize them.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Check the Linux OS, Python, and Node.js versions in my sandbox.")
            .performScrollTo()
            .performClick()
        assertEquals("Check the Linux OS, Python, and Node.js versions in my sandbox.", selectedPrompt)

    }

    @Test
    fun agentInputBarHandlesAgentAndChatMode() {
        var currentMode by mutableStateOf(false)
        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Column {
                    ChatToolsActions(expanded = false, onToggle = {}, attachmentCount = 0)
                    AgentInputBar(
                        value = "Run check",
                        onValueChange = {},
                        isRunning = false,
                        isLoading = false,
                        error = "Turn failed",
                        onSend = {},
                        onStop = {},
                        onVoice = {},
                        chatMode = currentMode,
                        onChatMode = { currentMode = it },
                    )
                    AgentInputBar(
                        value = "",
                        onValueChange = {},
                        isRunning = false,
                        isLoading = false,
                        error = null,
                        onSend = {},
                        onStop = {},
                        onVoice = {},
                        chatMode = null,
                    )
                }
            }
        }

        composeRule.onAllNodesWithText("Agent").assertCountEquals(1)
        composeRule.onNodeWithText("Chat").performClick()
        assertEquals(true, currentMode)
        composeRule.onAllNodesWithText("No tools · faster, cheaper").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("About chat modes").performClick()
        composeRule.onNodeWithText("Chat modes").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onAllNodesWithText("Generate images").assertCountEquals(0)
        composeRule.onNodeWithText("Turn failed").assertIsDisplayed()
    }

    @Test
    fun controlLabelsCanBeHiddenAndRestoredWithoutLosingAccessibleActions() {
        var chatMode by mutableStateOf(false)
        var selectedPrompt = ""
        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = false) {
                Column {
                    ChatToolsActions(expanded = false, onToggle = {}, attachmentCount = 0)
                    Box(Modifier.weight(1f)) {
                        EmptyAgentState(hasApiKey = true, onOpenSettings = {}, onSelectPrompt = { selectedPrompt = it })
                    }
                    AgentInputBar("", {}, false, false, null, {}, {}, {}, chatMode, { chatMode = it })
                }
            }
        }

        composeRule.onNodeWithText("Agent").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide control labels").performClick()
        composeRule.onNodeWithText("Agent").assertDoesNotExist()
        composeRule.onNodeWithText("Compare models").performScrollTo().performClick()
        assertEquals("Use Body Builder to compare Claude and Gemini on why stars shine.", selectedPrompt)
        composeRule.onNodeWithContentDescription("Chat").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(true, chatMode)
        composeRule.onNodeWithContentDescription("Show control labels").performClick()
        composeRule.onNodeWithText("Chat").assertIsDisplayed()
    }

    @Test
    fun controlLabelsDefaultToSymbolsAfterTheFirstDayUnlessExplicitlyChosen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstLaunch = 100L
        val nextDay = firstLaunch + TimeUnit.DAYS.toMillis(1)
        val preferences = ControlLabelPreferences(context)

        preferences.startOnboarding(firstLaunch)
        preferences.startOnboarding(firstLaunch + 100L)
        assertTrue(preferences.showLabels(nextDay - 1))
        assertFalse(preferences.showLabels(nextDay))
        preferences.setShowLabels(true)
        assertTrue(ControlLabelPreferences(context).showLabels(nextDay))
    }

    @Test
    fun conversationCardsReasoningRailAndSandboxTerminalHandleExpandAndFailureStates() {
        var clickedFile: ContainerFileRef? = null
        val stepWithFailure = ShellExecutionStep(
            containerId = "c8f2a91000xyz",
            commands = listOf("python3 sim.py"),
            results = listOf(
                ShellCommandResult(
                    command = "python3 sim.py",
                    stdout = "Mean: 7.002",
                    stderr = "Warning: low entropy",
                    outcome = "exit 1",
                )
            ),
            files = listOf(ContainerFileRef(fileId = "cfile_1", path = "/workspace/home/report.txt")),
        )
        val stepWithPendingCommandsOnly = ShellExecutionStep(
            containerId = null,
            commands = listOf("uname -a"),
            results = emptyList(),
            files = emptyList(),
        )
        val entry = ChatEntry(
            id = "msg-1",
            isUser = false,
            text = "Simulation complete.",
            reasoningSummary = "Checked distribution parameters.",
            shellSteps = listOf(stepWithFailure, stepWithPendingCommandsOnly),
            modelUsed = "anthropic/claude-3.7-sonnet",
            usage = TokenUsage(inputTokens = 400, outputTokens = 1000, totalTokens = 1400, cost = 0.0042),
        )

        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState()),
                ) {
                    UserMessageBubble("Run simulation")
                    AgentResponseCard(
                        entry = entry,
                        onCopyText = {},
                        onFileClick = { _, file -> clickedFile = file },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Run simulation").assertIsDisplayed()
        composeRule.onNodeWithText("Simulation complete.").assertIsDisplayed()
        // The footer shows only the cost until tapped, then the model and token details.
        composeRule.onNodeWithText("$0.0042").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Model: anthropic/claude-3.7-sonnet").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Reply cost").performScrollTo().performClick()
        composeRule.onNodeWithText("Model: anthropic/claude-3.7-sonnet").performScrollTo().assertIsDisplayed()

        // Expand reasoning rail
        composeRule.onNodeWithText("Reasoning").performScrollTo().performClick()
        composeRule.onNodeWithText("Checked distribution parameters.").performScrollTo().assertIsDisplayed()

        // Expand sandbox terminal cards and click file chip
        composeRule.onNodeWithText("Sandbox run 1").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("[exit 1]").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Warning: low entropy").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("/workspace/home/report.txt")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("cfile_1", clickedFile?.fileId)

        composeRule.onNodeWithText("Sandbox run 2").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("uname -a").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun liveAgentReplyAndRunStatusRenderTelemetryAndRespectShowReasoningFlag() {
        val progress = AgentProgress(
            started = 1_000L,
            lastEvent = 2_000L,
            step = 2,
            phase = "reasoning",
            model = "claude-3.7-sonnet",
            streamedChars = 1_280L,
            timeline = listOf(
                LiveBlock.Thinking("Analyzing sandbox environment"),
                LiveBlock.Answer("Partial stream"),
            ),
        )

        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Column {
                    AgentRunStatus(
                        progress = progress,
                        waitingForApproval = false,
                        following = false,
                        providerName = "OpenRouter",
                        onActivity = {},
                        onFollow = {},
                    )
                    LiveAgentReply(
                        progress = progress,
                        interrupted = false,
                        showReasoning = true,
                    )
                    LiveAgentReply(
                        progress = progress,
                        interrupted = false,
                        showReasoning = false,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Model is reasoning").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Jump to latest").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Run activity").assertIsDisplayed()
        composeRule.onAllNodesWithText("Thought").assertCountEquals(2)
        composeRule.onAllNodesWithText("Thought")[1].assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .assertCountEquals(2)
        composeRule.onAllNodesWithText("Analyzing sandbox environment").assertCountEquals(0)
        composeRule.onAllNodesWithText("Thought")[1].performClick()
        composeRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
            .assertCountEquals(1)
        composeRule.onNodeWithText("Analyzing sandbox environment").assertIsDisplayed()
        composeRule.onAllNodesWithText("Partial stream")[0].assertIsDisplayed()
        assertEquals(
            StudioLightColorScheme.onErrorContainer,
            agentResponseReportContentColor(isWarningCard = true, colors = StudioLightColorScheme),
        )
        assertEquals(
            StudioLightColorScheme.primary,
            agentResponseReportContentColor(isWarningCard = false, colors = StudioLightColorScheme),
        )
    }

    @Test
    fun streamedThinkingStartsCollapsedAndCanBeOpenedWhileRunning() {
        val progress = AgentProgress(started = 0L, phase = "reasoning", summary = "Thinking text")
        composeRule.setContent { OpenRouterAgentTheme { LiveAgentReply(progress, showReasoning = true) } }

        composeRule.onAllNodesWithText("Thinking text").assertCountEquals(0)
        composeRule.onNodeWithText("Thinking…").performClick()
        composeRule.onNodeWithText("Thinking text").assertIsDisplayed()
    }

    @Test
    fun streamedAndSavedRepliesRenderMarkdownByDefaultAndCanShowSource() {
        var renderMarkdown by mutableStateOf(true)
        val source = "A **bold** answer with `code`."
        composeRule.setContent {
            OpenRouterAgentTheme {
                androidx.compose.foundation.layout.Column {
                    AgentResponseCard(ChatEntry(isUser = false, text = source), {}, { _, _ -> },
                        renderMarkdown = renderMarkdown)
                    LiveAgentReply(AgentProgress(started = 0L, answer = source), renderMarkdown = renderMarkdown)
                }
            }
        }

        composeRule.onAllNodesWithText("A bold answer with code.").assertCountEquals(2)
        composeRule.onAllNodesWithText(source).assertCountEquals(0)
        composeRule.runOnIdle { renderMarkdown = false }
        composeRule.onAllNodesWithText(source).assertCountEquals(2)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tappingRenderedMarkdownLinkOpensItsDestination() {
        var openedUri: String? = null
        val handler = object : UriHandler {
            override fun openUri(uri: String) { openedUri = uri }
        }
        composeRule.setContent {
            OpenRouterAgentTheme {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    ReplyText("[Read the source](https://example.com/source)", renderMarkdown = true)
                }
            }
        }

        val link = composeRule.onNodeWithText("Read the source")
        val layout = mutableListOf<TextLayoutResult>()
        link.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        val textLayout = layout.single()
        val position = textLayout.getBoundingBox(2).center
        link.performTouchInput { click(position) }
        composeRule.runOnIdle {
            assertEquals("Tap at $position mapped to character ${textLayout.getOffsetForPosition(position)}",
                "https://example.com/source", openedUri)
        }
    }
}
