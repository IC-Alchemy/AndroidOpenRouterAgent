package com.example.openrouteragent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentResourceActionsTest {
    private val original = AgentConfig()
    private val researcher = """{"name":" Researcher ","model":"example/model","instructions":"Collect sources."}"""
    private val skill = """{"name":"research-notes","description":"Take notes","body":"Keep a log.","pinned":true}"""
    private val lookup = JSONObject().put("name", "lookup_note").put("description", "Find notes")
        .put("schema", """{"type":"object","properties":{"id":{"type":"number"}},"required":["id"]}""")
        .put("handler", "template").put("value", "Note {{id}}")

    private fun act(name: String, args: String, config: AgentConfig = original) =
        prepareResourceAction(config, name, JSONObject(args))

    @Test
    fun createsNamedSubagentWithoutChangingExistingHostedTools() {
        val seeded = original.copy(extraToolsJson = """[{"type":"openrouter:advisor"},{"type":"openrouter:subagent"}]""")
        val advanced = """{"maxToolCalls":4,"maxCompletionTokens":512,"temperature":0.3,"reasoning":{"effort":"low","maxTokens":30},"tools":[{"type":"openrouter:web_search","parameters":{"max_results":2}}],"inheritFunctions":false,"inheritedFunctionNames":[" lookup_note "]}"""
        val arguments = JSONObject(researcher).put("advancedJson", advanced).toString()
        val saved = act("create_subagent", arguments, seeded)
        val tools = Json.parseToJsonElement(saved.config.extraToolsJson).jsonArray
        assertEquals(readHostedTools(seeded.extraToolsJson), tools.take(2))
        val worker = tools.last().jsonObject
        assertTrue(worker.isSubagent())
        val parameters = worker.subagentParameters()!!
        assertEquals("Researcher", parameters.stringValue("name"))
        assertEquals("example/model", parameters.stringValue("model"))
        assertEquals(Json.parseToJsonElement(advanced).jsonObject,
            parameters.filterKeys { it !in setOf("name", "instructions", "model") })
        assertEquals("Researcher", saved.result.getString("name"))
        assertThrows(IllegalArgumentException::class.java) { act("create_subagent", arguments, saved.config) }
    }

    @Test
    fun omittedNameCreatesOneDefaultSubagentAlongsideNamedWorkers() {
        val existing = act("create_subagent", researcher).config
        val saved = act("create_subagent", """{"instructions":"Summarize findings."}""", existing)
        val workers = readHostedTools(saved.config.extraToolsJson)!!
        assertEquals("Researcher", workers.first().subagentParameters()?.stringValue("name"))
        assertEquals("Summarize findings.", workers.last().subagentParameters()?.stringValue("instructions"))
        assertNull(workers.last().subagentParameters()?.stringValue("name"))
        assertFalse(saved.result.has("name"))
        assertThrows(IllegalArgumentException::class.java) { act("create_subagent", "{}", saved.config) }
        assertThrows(IllegalArgumentException::class.java) { act("create_subagent", """{"name":" "}""", existing) }
    }

    @Test
    fun rejectsUnrecognizedWorkerToolsDuringChatCreationAndAcceptsManualExtensions() {
        for (type in listOf("openrouter:typo", "openrouter:subagent", "openrouter:mcp")) {
            val arguments = JSONObject(researcher).put("advancedJson", """{"tools":[{"type":"$type"}]}""")
            assertThrows(IllegalArgumentException::class.java) { act("create_subagent", arguments.toString()) }
        }
        val shadowedParameters = JSONObject(researcher).put("advancedJson",
            """{"tools":[{"type":"openrouter:web_search","additionalProperties":{"parameters":[]}}]}""")
        assertThrows(IllegalArgumentException::class.java) { act("create_subagent", shadowedParameters.toString()) }
        val legacy = original.copy(extraToolsJson =
            """[{"type":"openrouter:subagent","parameters":{"tools":[{"type":"openrouter:provider_extension"}]}}]""")
        assertNull(legacy.settingsError())
        assertEquals(readHostedTools(legacy.extraToolsJson)!!.single(),
            readHostedTools(act("create_subagent", researcher, legacy).config.extraToolsJson)!!.first())
    }

    @Test
    fun supportsSafeCustomHandlersAndRejectsDuplicateNames() {
        val formats = act("create_custom_tool", lookup.toString())
        val ask = JSONObject(lookup.toString()).put("name", "ask_for_note").put("handler", "ask").apply { remove("value") }
        val prompts = act("create_custom_tool", ask.toString(), formats.config)
        val webhook = JSONObject(lookup.toString()).put("name", "fetch_note").put("handler", "webhook")
            .put("value", "https://example.com/notes")
        val requests = act("create_custom_tool", webhook.toString(), prompts.config)
        assertEquals(listOf("lookup_note", "ask_for_note", "fetch_note"), requests.config.customTools.map { it.name })
        assertTrue(requests.config.customTools.all { it.enabled && it.problem() == null })
        assertTrue(original.customTools.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            act("create_custom_tool", lookup.toString(), requests.config)
        }
    }

    @Test
    fun reservesProjectFileFunctionNames() {
        for (name in listOf("list_project_files", "read_project_file", "write_project_file", "edit_project_file")) {
            val arguments = JSONObject(lookup.toString()).put("name", name)
            assertThrows(IllegalArgumentException::class.java) { act("create_custom_tool", arguments.toString()) }
        }
    }

    @Test
    fun savesSkillWithRequestedPinAndPreservesExistingResources() {
        val previous = act("create_custom_tool", lookup.toString()).config
        val saved = act("create_skill", skill, previous)
        assertEquals(previous.customTools, saved.config.customTools)
        val created = saved.config.savedSkills.single()
        assertEquals("research-notes", created.name)
        assertTrue(created.enabled)
        assertTrue(created.pinned)
        assertEquals("Keep a log.", created.body)
        assertThrows(IllegalArgumentException::class.java) { act("create_skill", skill, saved.config) }
        assertThrows(IllegalArgumentException::class.java) {
            act("create_skill", JSONObject(skill).put("name", GUIDE_NAME).toString())
        }
    }

    @Test
    fun enablesSupportedServerToolsAndRejectsExistingOnes() {
        var config = original.copy(enableShellSandbox = false, enableWebSearch = false, enableDateTime = false)
        for (name in listOf("shell", "web_search", "datetime", "web_fetch", "files", "advisor",
            "image_generation", "experimental__search_models", "fusion")) {
            val arguments = """{"type":"openrouter:$name"}"""
            val change = act("enable_server_tool", arguments, config)
            config = change.config
            assertEquals("openrouter:$name", change.result.getString("type"))
            assertThrows(IllegalArgumentException::class.java) { act("enable_server_tool", arguments, config) }
        }
        assertTrue(config.enableShellSandbox && config.enableWebSearch && config.enableDateTime &&
            config.webFetch && config.enableWorkspaceFiles)
        assertEquals(listOf("advisor", "image_generation", "experimental__search_models", "fusion"),
            readHostedTools(config.extraToolsJson)!!.map { it.stringValue("type")!!.removePrefix("openrouter:") })

        val fetch = original.copy(extraToolsJson = """[{"type":"openrouter:web_fetch","parameters":{"maxUses":2}}]""")
        assertThrows(IllegalArgumentException::class.java) {
            act("enable_server_tool", """{"type":"openrouter:web_fetch"}""", fetch)
        }
    }

    @Test
    fun rejectsMalformedValuesAliasesAndUnsupportedResources() {
        val malformed = mapOf(
            "create_subagent" to listOf("""{"name":true}""", """{"name":"Researcher","maxToolCalls":5}""",
                JSONObject(researcher).put("advancedJson", """{"max_tool_calls":2}""").toString(),
                JSONObject(researcher).put("advancedJson", """{"reasoning":{"max_tokens":10}}""").toString(),
                JSONObject(researcher).put("advancedJson", """{"tools":[{"type":"openrouter:web_search","foo":1}]}""").toString(),
                JSONObject(researcher).put("advancedJson", """{"tools":[{"type":"function"}]}""").toString()),
            "create_custom_tool" to listOf(JSONObject(lookup.toString()).put("name", "create_skill").toString(),
                JSONObject(lookup.toString()).put("schema", """{"type":"array","items":{}}""").toString(),
                JSONObject(lookup.toString()).put("schema", """{"type":"object","properties":{},"required":5}""").toString(),
                JSONObject(lookup.toString()).put("schema", """{"type":"object","properties":{"id":{"type":"number","minimum":"zero"}}}""").toString(),
                JSONObject(lookup.toString()).put("schema", """{"type":"object","properties":{},"additionalProperties":1}""").toString(),
                JSONObject(lookup.toString()).put("handler", "kotlin").toString(),
                JSONObject(lookup.toString()).put("handler", "webhook").put("value", "http://example.com").toString()),
            "create_skill" to listOf(JSONObject(skill).put("enabled", false).toString(),
                JSONObject(skill).put("pinned", "true").toString()),
            "enable_server_tool" to listOf("""{"type":"openrouter:subagent"}""", """{"type":"openrouter:unknown"}"""),
            "delete_skill" to listOf(skill),
        )
        malformed.forEach { (name, examples) ->
            examples.forEach { args -> assertThrows(IllegalArgumentException::class.java) { act(name, args) } }
        }
    }
}
