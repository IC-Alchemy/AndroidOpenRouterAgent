package com.example.openrouteragent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubagentSettingsTest {
    @Test
    fun acceptsDefaultAndNamedWorkersWithOtherServerTools() {
        val config = AgentConfig(extraToolsJson = """
            [
              {"type":"openrouter:subagent"},
              {"type":"openrouter:web_fetch"},
              {"type":"openrouter:subagent","parameters":{
                "name":" Release_notes-2 ", "model":"openai/gpt-5.2",
                "instructions":"Summarize the changes.", "maxToolCalls":25, "maxCompletionTokens":4000,
                "reasoning":{"effort":"low","maxTokens":256}, "temperature":0,
                "tools":[{"type":"openrouter:web_search"}], "inheritFunctions":false,
                "inheritedFunctionNames":[" lookup_order "]
              }}
            ]
        """.trimIndent())

        assertNull(config.settingsError())
    }

    @Test
    fun rejectsDuplicateNamesAfterTrimmingAndMoreThanOneDefaultWorker() {
        val duplicateNames = """[{"type":"openrouter:subagent","parameters":{"name":" Research "}},
            {"type":"openrouter:subagent","parameters":{"name":"Research"}}]"""
        val duplicateDefaults = """[{"type":"openrouter:subagent"},{"type":"openrouter:subagent","parameters":{}}]"""
        val duplicateOtherTools = """[{"type":"openrouter:advisor"},{"type":"openrouter:advisor"}]"""

        listOf(duplicateNames, duplicateDefaults, duplicateOtherTools).forEach {
            assertNotNull(it, AgentConfig(extraToolsJson = it).settingsError())
        }
    }

    @Test
    fun rejectsInvalidWorkerNamesModelsAndAdvancedOptions() {
        val invalid = listOf(
            """{"name":42}""", """{"name":" "}""", """{"name":"worker.one"}""",
            """{"name":"${"a".repeat(65)}"}""", """{"model":"model id"}""",
            """{"model":"openrouter:subagent"}""", """{"instructions":[]}""",
            """{"tools":[{"type":"openrouter:subagent"}]}""",
            """{"tools":[{"type":"function","name":"lookup_order"}]}""",
            """{"tools":[{"type":"openrouter:web_fetch","parameters":[]}]}""",
            """{"tools":{"type":"openrouter:web_fetch"}}""", """{"maxToolCalls":26}""",
            """{"maxToolCalls":1.5}""", """{"maxCompletionTokens":0}""",
            """{"maxCompletionTokens":"20"}""", """{"temperature":2.01}""", """{"temperature":"1"}""",
            """{"reasoning":{"effort":"unknown"}}""", """{"reasoning":{"maxTokens":1.5}}""",
            """{"reasoning":{"maxTokens":-1}}""", """{"reasoning":"low"}""",
            """{"inheritFunctions":"true"}""", """{"inheritedFunctionNames":[" "]}""",
            """{"inheritedFunctionNames":[12]}""", """null""",
        )

        invalid.forEach { parameters ->
            val json = """[{"type":"openrouter:subagent","parameters":$parameters}]"""
            assertNotNull(parameters, AgentConfig(extraToolsJson = json).settingsError())
        }
    }

    @Test
    fun workerEditsRetainPositionsAndOtherServerTools() {
        val original = checkNotNull(readHostedTools("""
            [
              {"type":"openrouter:subagent","parameters":{"name":"Research","maxToolCalls":4}},
              {"type":"openrouter:web_fetch","parameters":{"custom":true}},
              {"type":"openrouter:subagent","parameters":{"name":"Outline","instructions":"Make an outline."}}
            ]
        """.trimIndent()))
        val research = original[0]
        val edited = editSubagent(research, "  Find sources ", "", "", subagentAdvancedJson(research.subagentParameters()))
        val newDefault = editSubagent(null, "", "", "", "")
        val updated = original.withSubagentEdit(0, edited).withSubagentEdit(original.size, newDefault)
        val saved = checkNotNull(readHostedTools(writeHostedTools(updated)))

        assertEquals(listOf(edited, original[1], original[2], newDefault), saved)
        assertEquals("Find sources", saved[0].subagentParameters()?.stringValue("name"))
        assertEquals(research.subagentParameters()?.get("maxToolCalls"), saved[0].subagentParameters()?.get("maxToolCalls"))
        assertNull(saved[3].subagentParameters())
        assertNull(hostedToolsError(writeHostedTools(saved)))
        assertEquals(listOf(edited, original[1], newDefault), saved.withSubagentEdit(2, null))
    }
}
