package com.example.openrouteragent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OpenAiDirectTest {
    private fun types(tools: JSONArray) = (0 until tools.length()).map { tools.getJSONObject(it).getString("type") }

    @Test
    fun modelListKeepsChatModelsWithThePrefixNewestFirst() {
        val raw = """{"data":[
            {"id":"gpt-4o","created":100},
            {"id":"gpt-5","created":300},
            {"id":"o3-mini","created":200},
            {"id":"text-embedding-3-small","created":400},
            {"id":"gpt-image-1","created":500},
            {"id":"whisper-1","created":50},
            {"id":"gpt-4o-realtime-preview","created":600},
            {"id":"chatgpt-4o-latest","created":150}
        ]}"""
        val models = parseOpenAiModels(raw)
        assertEquals(listOf("openai-direct/gpt-5", "openai-direct/o3-mini", "openai-direct/chatgpt-4o-latest",
            "openai-direct/gpt-4o"), models.map { it.id })
        val gpt5 = models.first()
        assertEquals("OpenAI: gpt-5 (direct)", gpt5.name)
        assertTrue(gpt5.supportsTools)
        assertTrue("reasoning" in gpt5.parameters)
        assertFalse("temperature" in gpt5.parameters)
        assertTrue("image" in gpt5.inputModalities)
        val gpt4o = models.last()
        assertTrue("temperature" in gpt4o.parameters)
        assertFalse(models.first { it.id.endsWith("chatgpt-4o-latest") }.supportsTools)
    }

    @Test(expected = IOException::class)
    fun aModelListWithoutDataFails() {
        parseOpenAiModels("""{"error":{"message":"no"}}""")
    }

    @Test
    fun onlyPrefixedIdsAreOpenAiModels() {
        assertTrue(isOpenAiModel("openai-direct/gpt-5"))
        assertFalse(isOpenAiModel("openai/gpt-5"))
        assertFalse(isOpenAiModel(null))
        assertTrue(isOpenAiReasoningModel("openai-direct/o4-mini"))
        assertTrue(isOpenAiReasoningModel("openai-direct/gpt-5-mini"))
        assertFalse(isOpenAiReasoningModel("openai-direct/gpt-5-chat-latest"))
        assertFalse(isOpenAiReasoningModel("openai-direct/gpt-4.1"))
    }

    @Test
    fun requestBodyDropsOpenRouterFieldsAndToolsAndAddsOpenAiTools() {
        val raw = JSONObject()
            .put("model", "openai-direct/gpt-4.1")
            .put("stream", true)
            .put("temperature", 0.4)
            .put("session_id", "chat-1")
            .put("stop_server_tools_when", JSONArray())
            .put("provider", JSONObject().put("zdr", true))
            .put("plugins", JSONArray().put(JSONObject().put("id", "response-healing")))
            .put("models", JSONArray().put("openai/gpt-4o"))
            .put("top_k", 5)
            .put("tool_choice", "auto")
            .put("tools", JSONArray()
                .put(JSONObject().put("type", "function").put("name", "get_time").put("description", "Time")
                    .put("parameters", JSONObject().put("type", "object")).put("strict", JSONObject.NULL))
                .put(JSONObject().put("type", "openrouter:web_search"))
                .put(JSONObject().put("type", "openrouter:shell")))
            .put("input", "Hello")
        val hosted = OpenAiHostedTools(webSearch = true, imageGeneration = true, imageAspect = "16:9",
            imageQuality = "high", codeInterpreter = true, fileSearchStores = listOf("vs_abc"))
        val body = JSONObject(openAiRequestBody(raw.toString(), hosted))

        assertEquals("gpt-4.1", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(0.4, body.getDouble("temperature"), 0.0)
        listOf("session_id", "stop_server_tools_when", "provider", "plugins", "models", "top_k").forEach {
            assertFalse(it, body.has(it))
        }
        val tools = body.getJSONArray("tools")
        assertEquals(listOf("function", "web_search", "code_interpreter", "file_search", "image_generation"), types(tools))
        assertFalse(tools.getJSONObject(0).has("strict"))
        assertEquals("auto", tools.getJSONObject(2).getJSONObject("container").getString("type"))
        assertEquals("vs_abc", tools.getJSONObject(3).getJSONArray("vector_store_ids").getString(0))
        assertEquals("1536x1024", tools.getJSONObject(4).getString("size"))
        assertEquals("high", tools.getJSONObject(4).getString("quality"))
        assertEquals("auto", body.getString("tool_choice"))
        assertEquals("Hello", body.getString("input"))
    }

    @Test
    fun reasoningModelsLoseSamplingButKeepEffort() {
        val raw = JSONObject().put("model", "openai-direct/o3").put("temperature", 1.0).put("top_p", 0.9)
            .put("reasoning", JSONObject().put("effort", "high").put("summary", "auto").put("max_tokens", 100))
            .put("input", "Hi")
        val body = JSONObject(openAiRequestBody(raw.toString(), OpenAiHostedTools()))
        assertFalse(body.has("temperature"))
        assertFalse(body.has("top_p"))
        val reasoning = body.getJSONObject("reasoning")
        assertEquals("high", reasoning.getString("effort"))
        assertEquals("auto", reasoning.getString("summary"))
        assertFalse(reasoning.has("max_tokens"))
        assertFalse(body.has("tools"))
        assertFalse(body.has("tool_choice"))
    }

    @Test
    fun inputKeepsMessagesAndFunctionCallsAndDropsTheRest() {
        val input = JSONArray()
            .put(JSONObject().put("type", "message").put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", "Time?"))))
            .put(JSONObject().put("type", "reasoning").put("id", "rs_1").put("encrypted_content", "abc"))
            .put(JSONObject().put("type", "function_call").put("id", "fc_1").put("call_id", "call_1")
                .put("name", "get_time").put("arguments", "{}"))
            .put(JSONObject().put("type", "function_call_output").put("call_id", "call_1").put("output", "12:00"))
            .put(JSONObject().put("type", "openrouter:web_search").put("id", "ws_1"))
            .put(JSONObject().put("type", "message").put("role", "assistant").put("id", "msg_1")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Noon"))))
        val raw = JSONObject().put("model", "openai-direct/gpt-4o").put("input", input)
        val out = JSONObject(openAiRequestBody(raw.toString(), OpenAiHostedTools())).getJSONArray("input")
        assertEquals(listOf("message", "function_call", "function_call_output", "message"), types(out))
        val call = out.getJSONObject(1)
        assertFalse(call.has("id"))
        assertEquals("call_1", call.getString("call_id"))
        val assistant = out.getJSONObject(3)
        assertFalse(assistant.has("id"))
        val part = assistant.getJSONArray("content").getJSONObject(0)
        assertEquals("output_text", part.getString("type"))
        assertEquals(0, part.getJSONArray("annotations").length())
    }

    @Test(expected = IOException::class)
    fun openRouterModelsAreNeverRewrittenForOpenAi() {
        openAiRequestBody("""{"model":"openai/gpt-4o","input":"hi"}""", OpenAiHostedTools())
    }

    @Test
    fun toolChoiceNamingAnOpenRouterToolMapsToTheOpenAiTool() {
        val raw = JSONObject().put("model", "openai-direct/gpt-4o").put("input", "hi")
            .put("tool_choice", JSONObject().put("type", "openrouter:web_search"))
        val body = JSONObject(openAiRequestBody(raw.toString(), OpenAiHostedTools(webSearch = true)))
        assertEquals("web_search", body.getJSONObject("tool_choice").getString("type"))
    }

    @Test
    fun openAiRunSettingsTurnOffOpenRouterFeaturesAndHideTheKey() {
        val config = AgentConfig(apiKey = "sk-or-v1-secret", enableShellSandbox = true, enableWebSearch = true,
            enableDateTime = true, webFetch = true, imageGeneration = true, advisorTool = true, fusionTool = true,
            modelSearchTool = true, responseHealing = true, contextCompression = true,
            extraToolsJson = """[{"type":"openrouter:web_fetch"}]""", deviceTools = true, miniApps = true,
            sdk = SdkSettings(providerOrder = "openai", fallbackModels = "openai/gpt-4o",
                followUpModel = "anthropic/claude-sonnet-4", advancedJson = """{"reasoning":{"effort":"low"},"topK":4}"""))
        val run = config.forOpenAiRun()
        assertEquals(OPENAI_RUNTIME_PLACEHOLDER_KEY, run.apiKey)
        assertFalse(run.enableShellSandbox || run.enableWebSearch || run.enableDateTime || run.webFetch ||
            run.imageGeneration || run.advisorTool || run.fusionTool || run.modelSearchTool ||
            run.responseHealing || run.contextCompression)
        assertEquals("", run.extraToolsJson)
        assertTrue(run.deviceTools)
        assertTrue(run.miniApps)
        assertEquals("", run.sdk.providerOrder)
        assertEquals("", run.sdk.fallbackModels)
        assertEquals("", run.sdk.followUpModel)
        assertEquals("""{"reasoning":{"effort":"low"}}""", run.sdk.advancedJson)
    }

    @Test
    fun hostedToolsFollowSettingsAndToolChoiceNone() {
        val config = AgentConfig(openAiWebSearch = true, openAiCodeInterpreter = true,
            openAiFileSearchStores = "vs_one, vs_two")
        assertEquals(listOf("web_search", "code_interpreter", "file_search"), types(config.openAiHostedTools().toJson()))
        assertEquals(0, config.copy(sdk = SdkSettings(toolChoice = "none")).openAiHostedTools().toJson().length())
    }

    @Test
    fun vectorStoreIdsAreChecked() {
        assertNull(vectorStoreIdsError(""))
        assertNull(vectorStoreIdsError("vs_abc, vs_def"))
        assertNotNull(vectorStoreIdsError("file_123"))
        assertNotNull(vectorStoreIdsError((1..11).joinToString(",") { "vs_$it" }))
        assertEquals(listOf("vs_abc", "vs_def"), parseVectorStoreIds(" vs_abc ,vs_def,,"))
    }

    @Test
    fun settingsRejectTheSameKeyForBothServices() {
        assertNotNull(AgentConfig(apiKey = "same-key", openAiKey = "same-key").settingsError())
        assertNotNull(AgentConfig(openAiKey = "sk bad").settingsError())
        assertNull(AgentConfig(apiKey = "sk-or-v1-a", openAiKey = "sk-proj-b").settingsError())
    }

    @Test
    fun redactRemovesTheKey() {
        assertEquals("bad [API key] here", "bad sk-proj-x here".redact("sk-proj-x"))
        assertEquals("unchanged", "unchanged".redact(""))
    }
}
