package com.example.openrouteragent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLibraryTest {
    private val config = AgentConfig(apiKey = "mock-key")
    private val model = CatalogModel("mock/model", "Fixture", 30_000, setOf("tools"))

    @Test
    fun savedDisplayPreferencesDefaultToMarkdownAndKeepingReasoningWithoutALivePreview() {
        val defaults = Json.decodeFromString<AgentConfig>("""{"sdk":{"theme":"light"}}""")
        assertTrue(defaults.sdk.renderMarkdown)
        assertFalse(defaults.sdk.showReasoning)
        assertTrue(defaults.sdk.saveReasoning)
        assertTrue(runtimeOptions(defaults, Workspace(), "Prompt", model).getJSONObject("sdk").getBoolean("showReasoning"))

        val updated = defaults.copy(sdk = defaults.sdk.copy(renderMarkdown = false, saveReasoning = false))
        val restored = Json.decodeFromString<AgentConfig>(Json.encodeToString(AgentConfig.serializer(), updated))
        assertFalse(restored.sdk.renderMarkdown)
        assertFalse(restored.sdk.saveReasoning)
        assertFalse(runtimeOptions(restored, Workspace(), "Prompt", model).getJSONObject("sdk").getBoolean("showReasoning"))
        val withPreview = restored.copy(sdk = restored.sdk.copy(showReasoning = true))
        assertTrue(runtimeOptions(withPreview, Workspace(), "Prompt", model).getJSONObject("sdk").getBoolean("showReasoning"))
    }

    @Test
    fun durableSdkStateKeepsOneSessionPerChatAndDoesNotSendTranscriptTwice() {
        val state = """{"version":1,"id":"saved","messages":[],"status":"completed"}"""
        val chat = Workspace(accountTag = workspaceTag(config), sdkState = state,
            sdkHistory = """[{"type":"message","role":"user","content":"Legacy"}]""",
            sandboxDomains = config.sandboxDomains(), containerId = "sess_old")
        val restored = Json.decodeFromString<Workspace>(Json.encodeToString(Workspace.serializer(), chat))
        val request = runtimeOptions(config, restored, "Continue", model)
        assertEquals(state, request.getString("state"))
        assertEquals(0, request.getJSONArray("history").length())
        assertEquals(chat.id, request.getString("sessionId"))
        assertEquals("sess_old", request.getString("containerId"))
        assertNotEquals(chat.id, runtimeOptions(config, Workspace(), "New", model).getString("sessionId"))
    }

    @Test
    fun changingSandboxPolicyRetainsChatIdentityButResetsHostedSession() {
        val saved = Workspace(accountTag = workspaceTag(config), sandboxDomains = listOf("old.example"),
            containerId = "sess_old", sdkState = """{"fixture":true}""",
            messages = listOf(ChatEntry(isUser = true, text = "Earlier question")))
        val restored = restoreWorkspace(config, saved)
        assertEquals(saved.id, restored.id)
        assertEquals(saved.messages, restored.messages)
        assertNull(restored.containerId)
        assertNull(restored.sdkState)
        assertNotEquals(saved.id, runtimeOptions(config, restored, "Continue", model).getString("sessionId"))
    }

    @Test
    fun interruptedTextRemainsVisibleWithoutBecomingModelHistory() {
        val chat = Workspace(interruptedTurn = InterruptedTurn("  First\n question  ", "Partial result", "", "Stopped"))
        assertEquals("First question", chatTitle(chat))
        assertEquals(0, runtimeOptions(config, chat, "Retry", model).getJSONArray("history").length())
        val request = runtimeOptions(config, chat, "Retry", model).toString()
        assertFalse(request.contains("Partial result"))
    }
}
