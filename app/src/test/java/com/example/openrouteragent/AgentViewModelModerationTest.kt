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
            chatClient = CompatibleChatClient(base = { server.url("/v1/") }),
        )
        awaitCondition { !vm.isLoading }
        val mockProvider = CustomProvider(
            id = "mock-provider",
            name = "Mock Provider",
            baseUrl = "https://mock.example.com/v1/",
            apiKey = "",
            model = "mock/model",
            stream = false,
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"mock/model","name":"Mock","modality":"text"}]}""")
        )
        runBlocking {
            vm.saveSettings(
                AgentConfig(
                    activeProviderId = mockProvider.id,
                    customProviders = listOf(mockProvider),
                    enableModeration = enableModeration,
                )
            )
        }
        awaitCondition { !vm.catalogLoading }
        // Drain the /v1/models request issued by refreshModels() on provider switch.
        server.takeRequest()
        return vm
    }

    @Test
    fun moderationDefaultsToOffInConfigAndLegacyJson() {
        assertFalse(AgentConfig().enableModeration)
        val decoded = lenientJson.decodeFromString(AgentConfig.serializer(), "{}")
        assertFalse(decoded.enableModeration)
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
                    .setBody("""{"choices":[{"message":{"content":"Unfiltered AI response"},"finish_reason":"stop"}]}""")
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
                    .setBody("""{"choices":[{"message":{"content":"Normal response without engine"},"finish_reason":"stop"}]}""")
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
                    .setBody("""{"choices":[{"message":{"content":"First reply"},"finish_reason":"stop"}]}""")
            )
            vm.send("First prompt")
            awaitCondition { vm.workspace.messages.size == 2 && vm.activePrompt == null }
            assertTrue(moderator.calls.isEmpty())

            // Toggle ON and persist
            runBlocking { vm.saveSettings(vm.config.copy(enableModeration = true)) }
            assertTrue(AgentStorage(app).loadConfig().enableModeration)

            vm.send("Second prompt")
            awaitCondition { !vm.isModerating && vm.turnError != null }
            assertEquals(POLICY_PROMPT_BLOCKED, vm.turnError)
            assertEquals(listOf("Second prompt"), moderator.calls)

            // Toggle OFF and persist
            runBlocking { vm.saveSettings(vm.config.copy(enableModeration = false)) }
            assertFalse(AgentStorage(app).loadConfig().enableModeration)

            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"content":"Third reply"},"finish_reason":"stop"}]}""")
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

            assertEquals(1, server.requestCount) // Only the initial /v1/models from setup; 0 chat requests
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
                    .setBody("""{"choices":[{"message":{"content":"Unsafe AI output"},"finish_reason":"stop"}]}""")
            )

            vm.promptText = "Safe user prompt"
            vm.send(vm.promptText)

            awaitCondition(detail = {
                "msgs=${vm.workspace.messages.size}, active=${vm.activePrompt}, mod=${vm.isModerating}, " +
                    "loading=${vm.isLoading}, saving=${vm.isSavingSettings}, working=${vm.isWorkingChat}, " +
                    "ready=${vm.providerReady}, model=${vm.selectedModel?.id}, calls=${moderator.calls}, " +
                    "err=${vm.turnError}, storage=${vm.storageError}, reqs=${server.requestCount}"
            }) { vm.workspace.messages.size == 2 && vm.activePrompt == null }

            assertEquals(2, server.requestCount) // 1 initial /v1/models + 1 /v1/chat/completions
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

            val nextPayload = chatPayload(
                profile = vm.provider!!,
                config = vm.config,
                prior = vm.workspace,
                prompt = "Follow up",
                model = "mock/model",
            )
            val historyMessages = nextPayload.getJSONArray("messages")
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
                    .setBody("""{"choices":[{"message":{"content":"Helpful safe answer."},"finish_reason":"stop"}]}""")
            )

            vm.send("Explain Kotlin coroutines.")
            awaitCondition(detail = {
                "msgs=${vm.workspace.messages.size}, active=${vm.activePrompt}, mod=${vm.isModerating}, " +
                    "loading=${vm.isLoading}, saving=${vm.isSavingSettings}, working=${vm.isWorkingChat}, " +
                    "ready=${vm.providerReady}, model=${vm.selectedModel?.id}, calls=${moderator.calls}, " +
                    "err=${vm.turnError}, storage=${vm.storageError}, reqs=${server.requestCount}"
            }) { vm.workspace.messages.size == 2 && vm.activePrompt == null }

            assertEquals(2, server.requestCount) // 1 initial /v1/models + 1 /v1/chat/completions
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
            server.enqueue(MockResponse().setResponseCode(200))
            val vm = createConfiguredViewModel(server, RecordingTextModerator { true })

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
