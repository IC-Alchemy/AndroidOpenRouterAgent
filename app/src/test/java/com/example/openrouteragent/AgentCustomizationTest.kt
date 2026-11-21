package com.example.openrouteragent

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentCustomizationTest {
    @Test
    fun skillMarkdownRoundTripsAndImportsForReview() {
        val markdown = """
            ---
            name: writing-notes
            description: >-
              Use when the user needs
              a short notes summary.
            metadata:
              owner: "Example"
            ---
            # Instructions
            Write three bullets.
        """.trimIndent()
        val skill = parseSkill(markdown)
        assertEquals("writing-notes", skill.name)
        assertEquals("Use when the user needs a short notes summary.", skill.description)
        assertTrue(skill.body.startsWith("# Instructions"))
        assertFalse(skill.enabled)
        assertFalse(skill.pinned)
        assertEquals(skill, parseSkill(skill.markdown()))
        assertEquals("123", parseSkill(skill.copy(name = "123").markdown()).name)
    }

    @Test
    fun rejectsUnsafeOrMalformedSkillMetadataAndToolSchemas() {
        val invalid = listOf("---\nname: ../../foo\ndescription: test\n---\nbody",
            "---\nname: one\nname: two\ndescription: test\n---\nbody",
            "---\nname: !!java.net.URL 'https://example.com/'\ndescription: test\n---\nbody")
        invalid.forEach { assertTrue(runCatching { parseSkill(it) }.isFailure) }
        val tool = CustomAgentTool(name = "safe_tool", description = "Format a response", value = "{{title}}")
        assertNull(tool.problem())
        assertNotNull(tool.copy(name = "load_skill").problem())
        assertNotNull(tool.copy(handler = "webhook", value = "http://example.com").problem())
        assertNotNull(tool.copy(schema = """{"type":"object","properties":{},"required":["missing"]}""").problem())
    }

    @Test
    fun apiSettingsAndCustomizationSerializeWithoutLosingDefaults() {
        val original = AgentConfig(sdk = SdkSettings(followUpTurn = 3, followUpModel = "example/fast",
            maxRunTokens = 12000, topP = 0.8, advancedJson = """{"metadata":{"task":"draft"}}"""))
        val saved = Json.encodeToString(AgentConfig.serializer(), original)
        assertEquals(original, Json.decodeFromString(AgentConfig.serializer(), saved))
        assertEquals("oled", Json.decodeFromString(AgentConfig.serializer(), "{}").sdk.theme)
        assertNull(original.settingsError())
        assertNotNull(original.copy(sdk = original.sdk.copy(advancedJson = """{"apiKey":"no"}""")).settingsError())
    }
}
