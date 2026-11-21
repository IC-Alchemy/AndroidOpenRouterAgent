package com.example.openrouteragent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class OpenRouterAgentClientTest {
    @Test
    fun queriesTheAuthenticatedCatalogAndSelectsAnEligibleModel() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""
            {"data":[
              {"id":"mock/plain","name":"Plain model","context_length":1000,"supported_parameters":["temperature"]},
              {"id":"mock/tools","name":"Tool model","context_length":16000,"supported_parameters":["tools"]},
              {"id":"mock/tools","name":"Duplicate"}
            ]}
        """.trimIndent()))
        val models = client.availableModels("fake-key")
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/models/user", request.path)
        assertEquals("Bearer fake-key", request.getHeader("Authorization"))
        assertEquals(2, models.size)
        assertEquals("mock/tools", selectModel(AgentConfig(model = "mock/plain"), models)?.id)
        val plain = AgentConfig(model = "mock/plain", enableShellSandbox = false,
            enableWebSearch = false, enableDateTime = false)
        assertEquals("mock/plain", selectModel(plain, models)?.id)
        assertNull(selectModel(AgentConfig(), models.take(1)))
    }

    @Test
    fun runtimeReceivesPersistedItemsAndOnlyProviderSupportedTemperature() {
        val config = AgentConfig(temperature = 0.4, allowSandboxNetwork = true,
            allowedDomainsCsv = " PYPI.org,pypi.org,files.pythonhosted.org ")
        val model = CatalogModel("mock/model", "Mock", 16000, setOf("tools"))
        val history = """[{"type":"function_call_output","callId":"c1","output":"result"}]"""
        val options = runtimeOptions(config, Workspace(sdkHistory = history), "Next prompt", model)
        assertFalse(options.has("temperature"))
        assertEquals("c1", options.getJSONArray("history").getJSONObject(0).getString("callId"))
        assertEquals("""["files.pythonhosted.org","pypi.org"]""", options.getJSONArray("domains").toString())
        val supporting = model.copy(parameters = setOf("tools", "temperature"))
        assertEquals(0.4, runtimeOptions(config, Workspace(), "Prompt", supporting).getDouble("temperature"), 0.0)
        assertNotNull(config.copy(allowedDomainsCsv = "*").settingsError())
        assertNotNull(config.copy(mcpUrl = "http://example.com").settingsError())
    }

    @Test
    fun parsesHostedToolFilesAndCitationsAndPersistsTheirMetadata() {
        val result = parseAgentResult("""
            {
              "status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"model":"vendor/model",
              "output":[
                {"type":"message","phase":"commentary","content":[{"type":"output_text","text":"I'll check."}]},
                {"type":"openrouter:shell","container_id":"sess_abc","action":{"commands":["python --version","make report"]},
                 "output":[{"stdout":"Python 3.11","outcome":{"type":"exit","exit_code":0}},
                           {"stderr":"Test failure","outcome":{"type":"exit","exit_code":10}}],
                 "files":[{"type":"container_file_citation","file_id":"cfile_abc","filename":"outputs/report.txt"}]},
                {"type":"web_search_call","action":{"query":"OpenRouter","queries":["One","Two","One"]}},
                {"type":"reasoning","summary":[{"type":"summary_text","text":"Summary"}]},
                {"type":"message","phase":"final_answer","content":[{"type":"output_text","text":"Here is the report.",
                    "annotations":[{"type":"url_citation","url":"https://example.com","title":"Example"},
                                   {"type":"url_citation","url":"file:///etc/hosts"}]}]}
              ],
              "usage":{"input_tokens":50,"output_tokens":20,"total_tokens":70}
            }
        """.trimIndent())
        assertEquals("Here is the report.", result.assistantText)
        assertEquals("sess_abc", result.discoveredContainerId)
        assertEquals("outputs/report.txt", result.shellSteps.single().files.single().path)
        assertEquals("make report", result.shellSteps.single().results[1].command)
        assertEquals("exit 10", result.shellSteps.single().results[1].outcome)
        assertEquals(listOf("OpenRouter", "One", "Two"), result.searchQueries)
        assertEquals(WebCitation("Example", "https://example.com"), result.citations.single())
        assertEquals(TokenUsage(50, 20, 70), result.usage)
        assertTrue(result.notice.orEmpty().contains("max output tokens"))
        val workspace = Workspace(messages = listOf(ChatEntry(isUser = false, text = result.assistantText,
            shellSteps = result.shellSteps, citations = result.citations)), containerId = result.discoveredContainerId)
        val serialized = Json.encodeToString(Workspace.serializer(), workspace)
        assertEquals(workspace, Json.decodeFromString(Workspace.serializer(), serialized))
    }

    @Test
    fun joinsNativeCallAndOutputByCallId() {
        val result = parseAgentResult("""
            {"output":[
              {"type":"shell_call","call_id":"c1","action":{"commands":["one"]}},
              {"type":"shell_call","call_id":"c2","action":{"commands":["two"]}},
              {"type":"shell_call_output","call_id":"c2","container_id":"sess","output":[{"stdout":"2"}]},
              {"type":"shell_call_output","call_id":"c1","output":[{"stdout":"1","outcome":{"type":"timeout"}}]}
            ]}
        """.trimIndent())
        assertEquals("two", result.shellSteps[0].results.single().command)
        assertEquals("one", result.shellSteps[1].results.single().command)
        assertEquals("timeout", result.shellSteps[1].results.single().outcome)
    }

    @Test
    fun paginatesFilesAndSavesBinaryContentUnchanged() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""
            {"data":[{"id":"cfile_a","path":"outputs/report.zip","bytes":4}],"has_more":true,"last_id":"cfile_a"}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""{"data":[],"has_more":false,"last_id":null}"""))
        val bytes = byteArrayOf(80, 75, 0, -1)
        repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(bytes))) }

        val first = client.listFiles("fake-key", "sess_abc", null)
        assertEquals("cfile_a", first.after)
        assertEquals(4L, first.files.single().bytes)
        assertNull(client.listFiles("fake-key", "sess_abc", first.after).after)
        assertNull(client.previewFile("fake-key", "sess_abc", "cfile_a").text)
        val destination = ByteArrayOutputStream()
        client.downloadFile("fake-key", "sess_abc", "cfile_a") { destination }

        assertArrayEquals(bytes, destination.toByteArray())
        assertEquals("/api/v1/containers/sess_abc/files?limit=100", server.takeRequest().path)
        assertEquals("/api/v1/containers/sess_abc/files?limit=100&after=cfile_a", server.takeRequest().path)
        repeat(2) {
            assertEquals("/api/v1/containers/sess_abc/files/cfile_a/content", server.takeRequest().path)
        }
    }

    @Test
    fun previewBoundsBytesWithoutMisclassifyingSplitUtf8Character() = withServer { server, client ->
        val prefix = "a".repeat(65_535)
        server.enqueue(MockResponse().setBody(prefix + "🎵"))
        val preview = client.previewFile("fake-key", "sess", "cfile_a")
        assertTrue(preview.truncated)
        assertEquals(prefix, preview.text)
    }

    @Test
    fun reportsFailedRunsAndRedactsCredentialsFromHttpErrors() = withServer { server, client ->
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Rejected fake-key"}}"""))
        val authError = runCatching { client.validateKey("fake-key") }.exceptionOrNull()
        assertTrue(authError is IOException)
        assertTrue(authError?.message.orEmpty().contains("401"))
        assertFalse(authError?.message.orEmpty().contains("fake-key"))
        assertEquals("/api/v1/key", server.takeRequest().path)
        val failure = runCatching {
            parseAgentResult("""{"status":"failed","error":{"message":"Provider failed"}}""")
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("Provider failed"))
    }

    @Test
    fun aMissingSandboxTextFileFailsWithItsPathAndAFix() = withServer { server, client ->
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"File not found"}}"""))
        val error = runCatching { client.previewFile("fake-key", "sess_old", "cfile_gone", "outputs/videos.txt") }
            .exceptionOrNull()
        assertTrue(error is java.io.FileNotFoundException)
        val message = error?.message.orEmpty()
        assertTrue(message, "outputs/videos.txt" in message && "sess_old" in message)
        assertTrue(message, "expires" in message && "Run the command again" in message)
    }

    @Test
    fun aStalledSandboxFilePreviewFailsAtThePreviewTimeout() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("hello").setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS))
            val client = OpenRouterAgentClient(server.url("/api/v1/"), previewTimeoutSeconds = 1)
            val started = System.nanoTime()
            val error = runCatching {
                runBlocking { client.previewFile("fake-key", "sess", "cfile_a", "notes.txt") }
            }.exceptionOrNull()
            val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("took $elapsed ms", elapsed < 5_000)
            assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("notes.txt"))
        }
    }

    private fun withServer(block: suspend (MockWebServer, OpenRouterAgentClient) -> Unit) {
        MockWebServer().use { server ->
            runBlocking { block(server, OpenRouterAgentClient(server.url("/api/v1/"))) }
        }
    }
}
