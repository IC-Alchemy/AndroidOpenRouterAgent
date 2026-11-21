package com.example.openrouteragent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class CompatibleChatClientTest {
    @Test
    fun discoversOnlyAvailableTextModelsForOpenUncensored() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""
            {"data":[
              {"id":"uncensored/chat","name":"Chat","modality":"text","available":true},
              {"id":"uncensored/offline","modality":"text","available":false},
              {"id":"uncensored/image","modality":"image","available":true}
            ]}
        """.trimIndent()))
        val models = client.models(profile)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models?available=true", request.path)
        assertEquals("Bearer fake-provider-key", request.getHeader("Authorization"))
        assertEquals(listOf("uncensored/chat"), models.map { it.id })
    }

    @Test
    fun streamsTextBeforeTheRequestFinishesAndSendsProfileRouting() = withServer { server, client ->
        val first = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n"
        server.enqueue(sse(first + """
            data: {"model":"routed-model","choices":[{"delta":{"content":" there"},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11},"ou":{"cost_usd":0.001}}

            data: [DONE]
        """.trimIndent() + "\n\n").throttleBody(first.length.toLong(), 250, TimeUnit.MILLISECONDS))
        kotlinx.coroutines.coroutineScope {
            val firstUpdate = CompletableDeferred<String>()
            val updates = mutableListOf<RunUpdate>()
            val run = async {
                client.run(profile, AgentConfig(apiKey = "fake-openrouter-key"), Workspace(),
                    "Say hello.", profile.model) { batch ->
                    updates += batch
                    batch.filterIsInstance<RunUpdate.Text>().firstOrNull()?.let { firstUpdate.complete(it.text) }
                }
            }
            assertEquals("Hello", withTimeout(5_000) { firstUpdate.await() })
            assertFalse(run.isCompleted)
            val result = run.await()
            assertEquals("Hello there", result.assistantText)
            assertEquals("routed-model", result.modelUsed)
            assertEquals(TokenUsage(9, 2, 11, 0.001), result.usage)
            assertEquals("Hello there", updates.filterIsInstance<RunUpdate.Text>().joinToString("") { it.text })
        }
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer fake-provider-key", request.getHeader("Authorization"))
        assertEquals("deepseek-v4.1-flash-1017-uncensored", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("quality", body.getJSONObject("routing").getString("strategy"))
        assertEquals("Say hello.", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals(1, body.getJSONArray("messages").length())
    }

    @Test
    fun surfacesStreamErrorsAndKeepsDeliveredText() = withServer { server, client ->
        server.enqueue(sse("""
            data: {"choices":[{"delta":{"content":"Partial reply"}}]}

            event: error
            data: {"error":{"message":"Provider stopped fake-provider-key"}}
        """.trimIndent() + "\n\n"))
        val updates = mutableListOf<RunUpdate>()
        val failure = runCatching {
            client.run(profile, AgentConfig(), Workspace(), "Hello", profile.model) { updates += it }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("Provider stopped [API key]", failure?.message)
        assertEquals("Partial reply", updates.filterIsInstance<RunUpdate.Text>().single().text)
    }

    @Test
    fun supportsJsonRepliesForProvidersWithoutStreamingOrDiscovery() = withServer { server, client ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"A reply"},"finish_reason":"length"}]}"""))
        val manual = CustomProvider(name = "Private gateway", baseUrl = "https://example.com/llm/v1/chat/completions",
            model = "custom/chat", stream = false, requestJson = """{"max_tokens":100,"seed":42}""",
            systemInstructions = "Answer briefly.")
        val config = AgentConfig(systemInstructions = "OpenRouter instructions", sdk = SdkSettings(outputTokens = 10))
        val history = Workspace(messages = listOf(ChatEntry(isUser = true, text = "Earlier turn"),
            ChatEntry(isUser = false, text = "Earlier reply"), ChatEntry(isUser = false, isError = true, text = "Old error")))
        val result = client.run(manual, config, history, "Continue", manual.model) { }
        assertEquals("A reply", result.assistantText)
        assertNotNull(result.notice)
        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertFalse(body.getBoolean("stream"))
        assertEquals(100, body.getInt("max_tokens"))
        assertEquals(42, body.getInt("seed"))
        val messages = body.getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("Answer briefly.", messages.getJSONObject(0).getString("content"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("Continue", messages.getJSONObject(3).getString("content"))
    }

    @Test
    fun normalizesProviderUrlsAndKeepsTranscriptsScopedToTheConnection() {
        val manual = CustomProvider(name = "Private gateway", baseUrl = "https://example.com/llm/v1/chat/completions")
        assertEquals("https://example.com/llm/v1/", manual.normalized().baseUrl)
        assertNull(manual.error())
        assertNotNull(manual.copy(baseUrl = "http://example.com/v1").error())
        assertNotNull(manual.copy(requestJson = """{"tools":[]}""").error())
        val config = AgentConfig(activeProviderId = manual.id, customProviders = listOf(manual))
        val workspace = Workspace(accountTag = workspaceTag(config),
            messages = listOf(ChatEntry(isUser = true, text = "Provider-specific message")))
        assertEquals(workspace, restoreWorkspace(config, workspace))
        assertTrue(restoreWorkspace(config.copy(activeProviderId = OPENROUTER_ID), workspace).messages.isEmpty())
        assertTrue(restoreWorkspace(config.copy(customProviders = listOf(manual.copy(apiKey = "new-key"))),
            workspace).messages.isEmpty())
        assertTrue(restoreWorkspace(config.copy(customProviders = listOf(manual.copy(baseUrl = "https://another.dev/v1"))),
            workspace).messages.isEmpty())
    }

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun withServer(test: suspend (MockWebServer, CompatibleChatClient) -> Unit) {
        MockWebServer().use { server ->
            val client = CompatibleChatClient(base = { server.url("/v1/") })
            runBlocking { test(server, client) }
        }
    }

    private val profile = openUncensoredProvider().copy(apiKey = "fake-provider-key")
}
