package com.example.openrouteragent

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentViewModelModerationTest {

    private class RecordingTextModerator(
        private val handler: suspend (String) -> Boolean,
    ) : TextModerator {
        val calls = mutableListOf<String>()

        override suspend fun isSafe(text: String): Boolean {
            calls += text
            return handler(text)
        }
    }

    private fun awaitCondition(
        timeoutMs: Long = 5_000L,
        detail: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out after ${timeoutMs}ms waiting for condition (${detail()})")
            }
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun createConfiguredViewModel(
        server: MockWebServer,
        moderator: TextModerator?,
        enableModeration: Boolean = false,
    ): AgentViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AgentViewModel(
            application = app,
            textModerator = moderator,
            reportClient = ModerationReportClient(endpointUrl = server.url("/report").toString()),
            client = OpenRouterAgentClient(baseUrl = server.url("/v1/")),
            sdk = TestAgentSdkRuntime(app, server),
            storage = testAgentStorage(app),
        )
        awaitCondition { !vm.isLoading }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"mock/model","name":"Mock","supported_parameters":["tools"]}]}""")
        )
        runBlocking {
            vm.saveSettings(
                AgentConfig(
                    apiKey = "test-openrouter-key",
                    model = "mock/model",
                    enableModeration = enableModeration,
                )
            )
        }
        awaitCondition { !vm.catalogLoading }
        assertEquals("/v1/models/user", server.takeRequest().path)
        return vm
    }

    @Test
    fun moderationDefaultsToOffInConfigAndLegacyJson() {
        assertFalse(AgentConfig().enableModeration)
        val decoded = lenientJson.decodeFromString(AgentConfig.serializer(), "{}")
        assertFalse(decoded.enableModeration)
    }

    @Test
    fun savedChatKeepsReasoningByDefaultAndOmitsItForNewRepliesWhenDisabled() {
        MockWebServer().use { server ->
            val vm = createConfiguredViewModel(server, moderator = null)
            val app = ApplicationProvider.getApplicationContext<Application>()
            ChatDatabase(app).use { chats ->
                val scope = chatScope(vm.config)
                server.enqueue(MockResponse().setBody("First answer").setHeader("X-Test-Reasoning", "First thought"))
                vm.send("First prompt")
                awaitCondition { vm.activePrompt == null && vm.workspace.messages.size == 2 }
                assertEquals("First thought", chats.active(scope)?.messages?.last()?.reasoningSummary)

                runBlocking { vm.saveSettings(vm.config.copy(sdk = vm.config.sdk.copy(saveReasoning = false))) }
                server.enqueue(MockResponse().setBody("Second answer").setHeader("X-Test-Reasoning", "Second thought"))
                vm.send("Second prompt")
                awaitCondition { vm.activePrompt == null && vm.workspace.messages.size == 4 }
                val saved = checkNotNull(chats.active(scope))
                assertEquals("First thought", saved.messages[1].reasoningSummary)
                assertEquals("Second answer", saved.messages.last().text)
                assertNull(saved.messages.last().reasoningSummary)
            }
        }
    }

    @Test
    fun interruptedReasoningRemainsAvailableDuringTheSessionWhenSavingIsOff() {
        MockWebServer().use { server ->
            val vm = createConfiguredViewModel(server, moderator = null)
            runBlocking { vm.saveSettings(vm.config.copy(sdk = vm.config.sdk.copy(saveReasoning = false))) }
            server.enqueue(MockResponse().setResponseCode(500).setHeader("X-Test-Reasoning", "Partial thought"))
            vm.send("First prompt")
            awaitCondition { vm.activePrompt == null && vm.interrupted != null }
            assertEquals("Partial thought", vm.interrupted?.summary)
            ChatDatabase(ApplicationProvider.getApplicationContext<Application>()).use { chats ->
                val saved = checkNotNull(chats.active(chatScope(vm.config))?.interruptedTurn)
                assertEquals("First prompt", saved.prompt)
                assertEquals("", saved.reasoningSummary)
            }
        }
    }

    @Test
    fun moderationDisabledByDefaultSkipsModeratorCallsAndAllowsAllPromptsAndResponses() {
        MockWebServer().use { server ->
            val calls = mutableListOf<String>()
            val failIfCalledModerator = object : TextModerator {
                override suspend fun isSafe(text: String): Boolean {
                    calls += text
                    error("TextModerator should not be called when moderation is disabled")
                }
            }
            val vm = createConfiguredViewModel(server, failIfCalledModerator, enableModeration = false)
            assertFalse(vm.config.enableModeration)

            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("Unfiltered AI response")
            )

            vm.promptText = "Potentially flagged user prompt"
            vm.send(vm.promptText)

            awaitCondition { vm.workspace.messages.size == 2 && vm.activePrompt == null }

            assertTrue(calls.isEmpty())
            assertFalse(vm.isModerating)
            assertNull(vm.turnError)
            assertEquals(2, server.requestCount)
            assertEquals("Potentially flagged user prompt", vm.workspace.messages[0].text)
            assertEquals("Unfiltered AI response", vm.workspace.messages[1].text)
            assertFalse(vm.workspace.messages[1].isModerated)
        }
    }

    @Test
    fun viewModelWorksWithoutModeratorInstanceEvenWhenModerationSettingIsOn() {
        MockWebServer().use { server ->
            val vm = createConfiguredViewModel(server, moderator = null, enableModeration = true)
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("Normal response without engine")
            )

            vm.send("Hello")
            awaitCondition { vm.workspace.messages.size == 2 && vm.activePrompt == null }

            assertFalse(vm.isModerating)
            assertNull(vm.turnError)
            assertEquals("Normal response without engine", vm.workspace.messages[1].text)
            assertFalse(vm.workspace.messages[1].isModerated)
        }
    }

    @Test
    fun togglingModerationInSettingsPersistsAndGatesFiltering() {
        MockWebServer().use { server ->
            val moderator = RecordingTextModerator { false }
            val vm = createConfiguredViewModel(server, moderator, enableModeration = false)
            val app = ApplicationProvider.getApplicationContext<Application>()

            // Off initially: request succeeds without consulting moderator
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("First reply")
            )
            vm.send("First prompt")
            awaitCondition { vm.workspace.messages.size == 2 && vm.activePrompt == null }
            assertTrue(moderator.calls.isEmpty())

            // Toggle ON and persist
            runBlocking { vm.saveSettings(vm.config.copy(enableModeration = true)) }
            assertTrue(testAgentStorage(app).loadConfig().enableModeration)

            vm.send("Second prompt")
            awaitCondition { !vm.isModerating && vm.turnError != null }
            assertEquals(POLICY_PROMPT_BLOCKED, vm.turnError)
            assertEquals(listOf("Second prompt"), moderator.calls)

            // Toggle OFF and persist
            runBlocking { vm.saveSettings(vm.config.copy(enableModeration = false)) }
            assertFalse(testAgentStorage(app).loadConfig().enableModeration)

            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("Third reply")
            )
            vm.send("Third prompt")
            awaitCondition { vm.workspace.messages.size == 4 && vm.activePrompt == null }
            assertEquals(listOf("Second prompt"), moderator.calls)
            assertEquals("Third reply", vm.workspace.messages[3].text)
            assertNull(vm.turnError)
        }
    }

    @Test
    fun unsafeUserPromptBlocksLocallyWithoutNetworkDispatchAndShowsPolicyError() {
        MockWebServer().use { server ->
            val moderator = RecordingTextModerator { false }
            val vm = createConfiguredViewModel(server, moderator, enableModeration = true)

            vm.promptText = "Flagged unsafe user prompt"
            vm.send(vm.promptText)

            awaitCondition { !vm.isModerating && vm.turnError != null }

            assertEquals(1, server.requestCount) // Only the initial /v1/models/user from setup; 0 chat requests
            assertNull(vm.activePrompt)
            assertEquals(POLICY_PROMPT_BLOCKED, vm.turnError)
            assertEquals("Flagged unsafe user prompt", vm.promptText)
            assertTrue(vm.workspace.messages.isEmpty())
            assertEquals(listOf("Flagged unsafe user prompt"), moderator.calls)
        }
    }

    @Test
    fun unsafeAiResponseRunsIsSafeOnceAndReplacesOutputWithPolicyWarning() {
        MockWebServer().use { server ->
            val moderator = RecordingTextModerator { text -> text == "Safe user prompt" }
            val vm = createConfiguredViewModel(server, moderator, enableModeration = true)
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("Unsafe AI output")
            )

            vm.promptText = "Safe user prompt"
            vm.send(vm.promptText)

            awaitCondition(detail = {
                "msgs=${vm.workspace.messages.size}, active=${vm.activePrompt}, mod=${vm.isModerating}, " +
                    "loading=${vm.isLoading}, saving=${vm.isSavingSettings}, working=${vm.isWorkingChat}, " +
                    "ready=${vm.providerReady}, model=${vm.selectedModel?.id}, calls=${moderator.calls}, " +
                    "err=${vm.turnError}, storage=${vm.storageError}, reqs=${server.requestCount}"
            }) { vm.workspace.messages.size == 2 && vm.activePrompt == null }

            assertEquals(2, server.requestCount) // 1 initial /v1/models/user + 1 /v1/responses
            assertEquals(listOf("Safe user prompt", "Unsafe AI output"), moderator.calls)
            val assistant = vm.workspace.messages[1]
            assertFalse(assistant.isUser)
            assertTrue(assistant.isModerated)
            assertEquals(POLICY_RESPONSE_WARNING, assistant.text)
            assertNull(assistant.reasoningSummary)
            assertTrue(assistant.shellSteps.isEmpty())
            assertTrue(assistant.searchQueries.isEmpty())
            assertTrue(assistant.citations.isEmpty())
            assertNull(assistant.notice)

            val nextPayload = runtimeOptions(
                config = vm.config,
                workspace = vm.workspace,
                prompt = "Follow up",
                model = checkNotNull(vm.selectedModel),
            )
            val historyMessages = nextPayload.getJSONArray("history")
            val contents = (0 until historyMessages.length()).map {
                historyMessages.getJSONObject(it).getString("content")
            }
            assertFalse(contents.contains(POLICY_RESPONSE_WARNING))
            assertFalse(contents.contains("Unsafe AI output"))
        }
    }

    @Test
    fun safePromptAndSafeAiResponseProceedNormally() {
        MockWebServer().use { server ->
            val moderator = RecordingTextModerator { true }
            val vm = createConfiguredViewModel(server, moderator, enableModeration = true)
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("Helpful safe answer.")
            )

            vm.send("Explain Kotlin coroutines.")
            awaitCondition(detail = {
                "msgs=${vm.workspace.messages.size}, active=${vm.activePrompt}, mod=${vm.isModerating}, " +
                    "loading=${vm.isLoading}, saving=${vm.isSavingSettings}, working=${vm.isWorkingChat}, " +
                    "ready=${vm.providerReady}, model=${vm.selectedModel?.id}, calls=${moderator.calls}, " +
                    "err=${vm.turnError}, storage=${vm.storageError}, reqs=${server.requestCount}"
            }) { vm.workspace.messages.size == 2 && vm.activePrompt == null }

            assertEquals(2, server.requestCount) // 1 initial /v1/models/user + 1 /v1/responses
            assertEquals(listOf("Explain Kotlin coroutines.", "Helpful safe answer."), moderator.calls)
            assertNull(vm.turnError)
            val assistant = vm.workspace.messages[1]
            assertFalse(assistant.isModerated)
            assertEquals("Helpful safe answer.", assistant.text)
        }
    }

    @Test
    fun reportResponseSubmitsOnlyPromptResponseAndReason() {
        MockWebServer().use { server ->
            val vm = createConfiguredViewModel(server, RecordingTextModerator { true })
            server.enqueue(MockResponse().setResponseCode(200))

            vm.reportResponse("Prompt A", "Response B", "Sexual content")
            awaitCondition { vm.reportMessage != null }

            assertEquals("Report submitted. Thank you.", vm.reportMessage)
            val request = server.takeRequest()
            assertEquals("/report", request.path)
            val payload = JSONObject(request.body.readUtf8())
            assertEquals(setOf("prompt", "response", "reason"), payload.keys().asSequence().toSet())
            assertEquals("Prompt A", payload.getString("prompt"))
            assertEquals("Response B", payload.getString("response"))
            assertEquals("Sexual content", payload.getString("reason"))
        }
    }

    private val lenientJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Test
    fun moderatedEntriesAreBackwardCompatibleInJsonAndExcludedFromSdkTranscript() {
        val oldJson = """{"id":"legacy","isUser":false,"text":"Legacy message"}"""
        val decoded = lenientJson.decodeFromString(ChatEntry.serializer(), oldJson)
        assertFalse(decoded.isModerated)

        val workspace = Workspace(
            messages = listOf(
                ChatEntry(isUser = true, text = "Safe question"),
                ChatEntry(isUser = false, text = POLICY_RESPONSE_WARNING, isModerated = true),
                ChatEntry(isUser = false, text = "Allowed reply"),
            )
        )
        val options = runtimeOptions(
            config = AgentConfig(apiKey = "test-key"),
            workspace = workspace,
            prompt = "Next question",
            model = CatalogModel("mock/model", "Mock", 10_000, emptySet()),
        )
        val history = options.getJSONArray("history")
        assertEquals(2, history.length())
        assertEquals("Safe question", history.getJSONObject(0).getString("content"))
        assertEquals("Allowed reply", history.getJSONObject(1).getString("content"))
    }
}
