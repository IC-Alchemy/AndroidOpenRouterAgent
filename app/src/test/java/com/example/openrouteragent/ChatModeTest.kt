package com.example.openrouteragent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModeTest {
    private val model = CatalogModel("mock/model", "Fixture", 30_000, setOf("tools"))
    private val agent = AgentConfig(apiKey = "mock-key", enableShellSandbox = true, enableWebSearch = true,
        enableDateTime = true, webFetch = true, deviceTools = true, enableWorkspaceFiles = true, miniApps = true,
        mcpUrl = "https://mcp.example.com/mcp", mcpToken = "t",
        extraToolsJson = """[{"type":"openrouter:fusion"}]""")

    @Test
    fun chatModeSendsNoToolsButKeepsTheConversationSettings() {
        assertTrue(agent.agentToolsEnabled())
        val chat = agent.withoutTools()
        assertFalse(chat.agentToolsEnabled())
        assertEquals("none", chat.sdk.toolChoice)
        assertEquals(agent.apiKey, chat.apiKey)
        assertEquals(agent.systemInstructions, chat.systemInstructions)
        assertEquals(agent.sdk.showReasoning, chat.sdk.showReasoning)
        val request = runtimeOptions(chat, Workspace(chatMode = true), "Hi", model)
        listOf("shell", "webSearch", "dateTime", "webFetch", "deviceTools", "workspaceFiles", "miniApps")
            .forEach { assertFalse(it, request.optBoolean(it)) }
        assertEquals("none", request.getJSONObject("sdk").getString("toolChoice"))
    }

    @Test
    fun theModeIsSavedWithTheChatAndOldChatsOpenInAgentMode() {
        val saved = Json.encodeToString(Workspace.serializer(), Workspace(chatMode = true))
        assertTrue(Json.decodeFromString(Workspace.serializer(), saved).chatMode)
        val old = """{"id":"a","messages":[]}"""
        assertFalse(Json { ignoreUnknownKeys = true }.decodeFromString(Workspace.serializer(), old).chatMode)
    }
}
