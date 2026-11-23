package com.example.openrouteragent

import kotlinx.coroutines.runBlocking
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

class CustomizationAssistantTest {
    private val jsonCapableModel = CatalogModel(
        id = "openai/gpt-5",
        name = "GPT-5",
        contextLength = 128_000,
        parameters = setOf("tools", "response_format"),
    )
    private val plainModel = CatalogModel(
        id = "anthropic/claude-sonnet-4.5",
        name = "Claude Sonnet 4.5",
        contextLength = 200_000,
        parameters = setOf("tools"),
    )

    @Test
    fun createsToolViaOpenRouterChatCompletionsWithOptionalResponseFormat() = withAssistant { server, assistant ->
        server.enqueue(chatJson("""
            ```json
            {
              "name": "format_bug",
              "description": "Format a bug report summary.",
              "handler": "template",
              "schema": {
                "type": "object",
                "properties": {
                  "title": {"type": "string"},
                  "severity": {"type": "string"}
                },
                "required": ["title"]
              },
              "value": "[{{severity}}] {{title}}"
            }
            ```
        """.trimIndent()))

        val config = AgentConfig(apiKey = "sk-or-secret")
        val update = assistant.create(config, jsonCapableModel, CustomizationKind.TOOL, "Create a bug formatter")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/chat/completions", request.path)
        assertEquals("Bearer sk-or-secret", request.getHeader("Authorization"))
        assertEquals("OpenRouter Sandbox Agent", request.getHeader("X-OpenRouter-Title"))

        val payload = JSONObject(request.body.readUtf8())
        assertEquals("openai/gpt-5", payload.getString("model"))
        assertFalse(payload.getBoolean("stream"))
        assertEquals("json_object", payload.getJSONObject("response_format").getString("type"))
        assertEquals(2, payload.getJSONArray("messages").length())

        val created = update.config.customTools.single()
        assertEquals("format_bug", created.name)
        assertEquals("template", created.handler)
        assertEquals("[{{severity}}] {{title}}", created.value)
        assertTrue(created.enabled)
        assertNull(created.problem(strictSchema = true))

        // Without response_format capability, response_format must be omitted.
        server.enqueue(chatJson("""
            {
              "name": "ping_check",
              "description": "Ask the user for status.",
              "handler": "ask",
              "schema": "{\"type\":\"object\",\"properties\":{\"note\":{\"type\":\"string\"}}}",
              "value": ""
            }
        """.trimIndent()))
        val second = assistant.create(update.config, plainModel, CustomizationKind.TOOL, "Create ask tool")
        val secondPayload = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(secondPayload.has("response_format"))
        assertEquals(2, second.config.customTools.size)
        assertEquals("ask", second.config.customTools.last().handler)
    }

    @Test
    fun createsSkillAndSubagentWithFlatOrNestedAdvancedFields() = withAssistant { server, assistant ->
        val config = AgentConfig(
            apiKey = "sk-or-test",
            extraToolsJson = """[{"type":"openrouter:advisor"}]""",
        )

        server.enqueue(chatJson("""
            Here is your skill:
            {"name":"kotlin-audit","description":"Audit Kotlin coroutines.","body":"# Steps\n1. Check cancellation.","pinned":true}
        """.trimIndent()))
        val skillUpdate = assistant.create(config, plainModel, CustomizationKind.SKILL, "Kotlin coroutine review")
        val skillReq = server.takeRequest()
        assertEquals("/api/v1/chat/completions", skillReq.path)
        assertEquals("Bearer sk-or-test", skillReq.getHeader("Authorization"))
        val reqBody = JSONObject(skillReq.body.readUtf8())
        assertEquals(plainModel.id, reqBody.getString("model"))

        val savedSkill = skillUpdate.config.savedSkills.single()
        assertEquals("kotlin-audit", savedSkill.name)
        assertTrue(savedSkill.pinned)
        assertTrue(savedSkill.enabled)

        // Subagent with both nested `advanced` and top-level SDK field `maxToolCalls`
        server.enqueue(chatJson("""
            {
              "name": "Researcher",
              "model": "openai/gpt-5-mini",
              "instructions": "Verify sources before answering.",
              "maxToolCalls": 6,
              "advanced": {
                "tools": [{"type": "openrouter:web_search"}],
                "temperature": 0.3
              }
            }
        """.trimIndent()))
        val subUpdate = assistant.create(skillUpdate.config, plainModel, CustomizationKind.SUBAGENT, "Web researcher")
        val tools = readHostedTools(subUpdate.config.extraToolsJson)!!
        assertEquals(2, tools.size)
        assertEquals("openrouter:advisor", tools[0].stringValue("type"))
        val worker = tools[1]
        assertTrue(worker.isSubagent())
        val params = worker.subagentParameters()!!
        assertEquals("Researcher", params.stringValue("name"))
        assertEquals("openai/gpt-5-mini", params.stringValue("model"))
        assertEquals("Verify sources before answering.", params.stringValue("instructions"))
        assertEquals(6, params["maxToolCalls"].toString().toInt())
        assertNotNull(params["tools"])
    }

    @Test
    fun improvesToolSkillAndSubagentInPlacePreservingOtherEntries() = withAssistant { server, assistant ->
        val t1 = CustomAgentTool(id = "tool-1", name = "alpha_tool", description = "Old alpha", value = "{{x}}", enabled = false)
        val t2 = CustomAgentTool(id = "tool-2", name = "beta_tool", description = "Keep beta", value = "{{y}}")
        val s1 = SavedAgentSkill(name = "note-taker", description = "Old notes", body = "Old body", enabled = true, pinned = true)
        val config = AgentConfig(
            apiKey = "sk-or-test",
            customTools = listOf(t1, t2),
            savedSkills = listOf(s1),
            extraToolsJson = """
                [
                  {"type":"openrouter:advisor"},
                  {"type":"openrouter:subagent","parameters":{"name":"Research","model":"openai/gpt-4.1","instructions":"Old."}},
                  {"type":"openrouter:subagent","parameters":{"name":"Writer","instructions":"Write."}}
                ]
            """.trimIndent(),
        )

        // Improve Tool 1 (preserves ID and list position 0, keeps enabled=false unless changed)
        server.enqueue(chatJson("""
            {
              "name": "alpha_tool",
              "description": "Improved alpha formatter.",
              "handler": "template",
              "schema": {"type":"object","properties":{"x":{"type":"string"}},"required":["x"]},
              "value": "Result: {{x}}"
            }
        """.trimIndent()))
        val afterTool = assistant.improve(
            config,
            jsonCapableModel,
            CustomizationTarget.Tool(t1),
            "Require x and format clearly",
        )
        val toolReq = JSONObject(server.takeRequest().body.readUtf8())
        val userMsg = toolReq.getJSONArray("messages").getJSONObject(1).getString("content")
        assertTrue(userMsg.contains("alpha_tool"))
        assertTrue(userMsg.contains("Require x and format clearly"))
        assertEquals(listOf("tool-1", "tool-2"), afterTool.config.customTools.map { it.id })
        assertEquals("Improved alpha formatter.", afterTool.config.customTools[0].description)
        assertFalse(afterTool.config.customTools[0].enabled)

        // Improve Skill (preserves pinned=true and rejects reserved guide name)
        server.enqueue(chatJson("""
            {"name":"note-taker","description":"Improved notes","body":"New concise body."}
        """.trimIndent()))
        val afterSkill = assistant.improve(
            afterTool.config,
            jsonCapableModel,
            CustomizationTarget.Skill(s1),
            "Make concise",
        )
        assertEquals("New concise body.", afterSkill.config.savedSkills.single().body)
        assertTrue(afterSkill.config.savedSkills.single().pinned)
        assertTrue(afterSkill.config.savedSkills.single().enabled)

        // Improve Subagent at index 1 while preserving advisor (index 0) and Writer (index 2)
        val workersBefore = readHostedTools(afterSkill.config.extraToolsJson)!!
        server.enqueue(chatJson("""
            {
              "name": "Research",
              "model": "google/gemini-2.5-pro",
              "instructions": "Cross-check two sources.",
              "advanced": {"maxToolCalls": 5}
            }
        """.trimIndent()))
        val afterSubagent = assistant.improve(
            afterSkill.config,
            jsonCapableModel,
            CustomizationTarget.Subagent(1, workersBefore[1]),
            "Use Gemini 2.5 Pro and cap tool calls at 5",
        )
        val workersAfter = readHostedTools(afterSubagent.config.extraToolsJson)!!
        assertEquals(3, workersAfter.size)
        assertEquals("openrouter:advisor", workersAfter[0].stringValue("type"))
        assertEquals("google/gemini-2.5-pro", workersAfter[1].subagentParameters()?.stringValue("model"))
        assertEquals("Cross-check two sources.", workersAfter[1].subagentParameters()?.stringValue("instructions"))
        assertEquals("Writer", workersAfter[2].subagentParameters()?.stringValue("name"))
    }

    @Test
    fun rejectsHttpErrorsWithRedactedKeysAndInvalidModelOutputs() = withAssistant { server, assistant ->
        val config = AgentConfig(apiKey = "sk-or-very-secret")

        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid key sk-or-very-secret"}}"""),
        )
        val authErr = runCatching {
            assistant.create(config, jsonCapableModel, CustomizationKind.SKILL, "Test")
        }.exceptionOrNull()
        assertTrue(authErr is IOException)
        assertFalse(authErr?.message.orEmpty().contains("sk-or-very-secret"))
        assertTrue(authErr?.message.orEmpty().contains("[API key]"))

        // Malformed non-JSON output
        server.enqueue(chatJson("Sorry, I cannot output JSON right now."))
        val parseErr = runCatching {
            assistant.create(config, jsonCapableModel, CustomizationKind.TOOL, "Test")
        }.exceptionOrNull()
        assertTrue(parseErr is IllegalArgumentException)

        // Non-HTTPS webhook rejected
        server.enqueue(chatJson("""
            {"name":"bad_hook","description":"Insecure hook","handler":"webhook","schema":{"type":"object","properties":{}},"value":"http://example.com/hook"}
        """.trimIndent()))
        val webhookErr = runCatching {
            assistant.create(config, jsonCapableModel, CustomizationKind.TOOL, "Create webhook")
        }.exceptionOrNull()
        assertTrue(webhookErr is IllegalArgumentException)

        // Invalid subagent maxToolCalls rejected
        server.enqueue(chatJson("""
            {"name":"Worker","advanced":{"maxToolCalls":99}}
        """.trimIndent()))
        val subErr = runCatching {
            assistant.create(config, jsonCapableModel, CustomizationKind.SUBAGENT, "Create worker")
        }.exceptionOrNull()
        assertTrue(subErr is IllegalArgumentException)

        // Duplicate tool name rejected on improve
        val existingTools = config.copy(
            customTools = listOf(
                CustomAgentTool(id = "a", name = "first_tool", description = "First", value = "1"),
                CustomAgentTool(id = "b", name = "second_tool", description = "Second", value = "2"),
            ),
        )
        server.enqueue(chatJson("""
            {"name":"second_tool","description":"Collision","handler":"template","schema":{"type":"object","properties":{}},"value":"x"}
        """.trimIndent()))
        val dupErr = runCatching {
            assistant.improve(existingTools, jsonCapableModel, CustomizationTarget.Tool(existingTools.customTools[0]), "Rename")
        }.exceptionOrNull()
        assertTrue(dupErr is IllegalArgumentException)

        // Reserved app guide name rejected for skills
        server.enqueue(chatJson("""
            {"name":"$GUIDE_NAME","description":"Guide","body":"Steps"}
        """.trimIndent()))
        val guideErr = runCatching {
            assistant.create(config, jsonCapableModel, CustomizationKind.SKILL, "Guide")
        }.exceptionOrNull()
        assertTrue(guideErr is IllegalArgumentException)
    }

    private fun chatJson(content: String): MockResponse {
        val body = JSONObject().put(
            "choices",
            org.json.JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put("role", "assistant").put("content", content),
                ),
            ),
        )
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }

    private fun withAssistant(block: suspend (MockWebServer, CustomizationAssistant) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val assistant = CustomizationAssistant(
                openRouterBaseUrl = server.url("/api/v1/"),
            )
            block(server, assistant)
        }
    }
}
