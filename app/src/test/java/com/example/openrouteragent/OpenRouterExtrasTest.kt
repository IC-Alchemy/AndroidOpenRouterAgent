package com.example.openrouteragent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterExtrasTest {
    private fun types(json: String, toggled: List<String>, toolsOff: Boolean = false) =
        runExtraTools(json, toggled, toolsOff).let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("type") } }

    private fun pluginIds(json: String) = JSONObject(json).getJSONArray("plugins")
        .let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("id") } }

    @Test
    fun switchedOnServerToolsJoinTheExtraTools() {
        val config = AgentConfig(advisorTool = true, fusionTool = true, modelSearchTool = true)
        assertEquals(listOf(ADVISOR_TOOL, FUSION_TOOL, MODEL_SEARCH_TOOL), types("", config.toggledServerTools()))
        assertEquals(listOf("openrouter:web_fetch", ADVISOR_TOOL),
            types("""[{"type":"openrouter:web_fetch"}]""", listOf(ADVISOR_TOOL)))
    }

    @Test
    fun theChosenAdvisorModelIsSentAsTheToolsModelParameter() {
        val config = AgentConfig(advisorTool = true, advisorModel = " anthropic/claude-opus-4.7 ")
        val array = runExtraTools("", config.toggledServerTools(), toolsOff = false, config.toggledServerToolParameters())
        assertEquals(ADVISOR_TOOL, array.getJSONObject(0).getString("type"))
        assertEquals("anthropic/claude-opus-4.7", array.getJSONObject(0).getJSONObject("parameters").getString("model"))
        // No chosen model, or the advisor switched off, sends no parameters.
        assertTrue(AgentConfig(advisorTool = true).toggledServerToolParameters().isEmpty())
        assertTrue(AgentConfig(advisorModel = "a/b").toggledServerToolParameters().isEmpty())
    }

    @Test
    fun aToolAlreadyInTheJsonKeepsItsSettings() {
        val array = runExtraTools("""[{"type":"openrouter:fusion","parameters":{"max_tool_calls":2}}]""",
            listOf(FUSION_TOOL), toolsOff = false)
        assertEquals(1, array.length())
        assertEquals(2, array.getJSONObject(0).getJSONObject("parameters").getInt("max_tool_calls"))
    }

    @Test
    fun noSwitchedOnToolIsAddedWhenToolsAreOff() {
        assertEquals(emptyList<String>(), types("", listOf(ADVISOR_TOOL), toolsOff = true))
        assertTrue(AgentConfig(advisorTool = true, fusionTool = true, modelSearchTool = true).withoutTools()
            .toggledServerTools().isEmpty())
        assertTrue(AgentConfig(fusionTool = true).agentToolsEnabled())
        assertFalse(AgentConfig(enableShellSandbox = false, enableWebSearch = false, enableDateTime = false,
            miniApps = false, responseHealing = true, contextCompression = true).agentToolsEnabled())
    }

    @Test
    fun switchedOnPluginsJoinTheAdvancedJson() {
        val config = AgentConfig(responseHealing = true, contextCompression = true)
        assertEquals(listOf(RESPONSE_HEALING_PLUGIN, CONTEXT_COMPRESSION_PLUGIN),
            pluginIds(advancedJsonWithPlugins("", config.toggledPlugins(), needsPdfParser = false)))
        val withOther = advancedJsonWithPlugins("""{"metadata":{"a":"b"}}""", listOf(RESPONSE_HEALING_PLUGIN), false)
        assertEquals("b", JSONObject(withOther).getJSONObject("metadata").getString("a"))
    }

    @Test
    fun pluginsStayAsTheyAreWhenNothingIsSwitchedOn() {
        assertEquals("", advancedJsonWithPlugins("", emptyList(), needsPdfParser = true))
        assertEquals("""{"plugins":"x"}""", advancedJsonWithPlugins("""{"plugins":"x"}""", listOf(RESPONSE_HEALING_PLUGIN), false))
    }

    @Test
    fun aPluginAlreadyInTheAdvancedJsonKeepsItsSettings() {
        val json = advancedJsonWithPlugins("""{"plugins":[{"id":"context-compression","enabled":false}]}""",
            listOf(CONTEXT_COMPRESSION_PLUGIN, RESPONSE_HEALING_PLUGIN), needsPdfParser = true)
        assertEquals(listOf(CONTEXT_COMPRESSION_PLUGIN, RESPONSE_HEALING_PLUGIN), pluginIds(json))
        assertFalse(JSONObject(json).getJSONArray("plugins").getJSONObject(0).getBoolean("enabled"))
    }

    @Test
    fun thePdfParserStaysWhenSwitchesAddPlugins() {
        val json = advancedJsonWithPlugins("", listOf(RESPONSE_HEALING_PLUGIN), needsPdfParser = true)
        assertEquals(listOf(RESPONSE_HEALING_PLUGIN, "file-parser"), pluginIds(json))
        assertEquals("cloudflare-ai", JSONObject(json).getJSONArray("plugins").getJSONObject(1)
            .getJSONObject("pdf").getString("engine"))
    }
}
