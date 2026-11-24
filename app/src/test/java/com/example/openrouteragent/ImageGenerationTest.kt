package com.example.openrouteragent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationTest {
    private val png = "1".repeat(64) + ".png"

    @Test
    fun savedImagesBecomeChatMediaWithTheirPrompt() {
        val result = parseAgentResult("""
            {"status":"completed","model":"vendor/painter","output":[
              {"type":"openrouter:image_generation","id":"ig_1","status":"completed",
               "image_b64":"oratt:b64:$png","revised_prompt":"A red bicycle by the sea"},
              {"type":"message","content":[{"type":"output_text","text":"Here you go."}]}
            ]}
        """.trimIndent())
        assertEquals("Here you go.", result.assistantText)
        assertEquals(listOf(ChatMedia(png, MEDIA_IMAGE, "image/png", "A red bicycle by the sea")), result.media)
        assertNull(result.notice)
    }

    @Test
    fun anImageWithNoTextStillGetsAReply() {
        val result = parseAgentResult("""
            {"status":"completed","output":[
              {"type":"image_generation_call","status":"completed","result":"oratt:b64:$png"}
            ]}
        """.trimIndent())
        assertEquals("Here is the image.", result.assistantText)
        assertEquals(png, result.media.single().file)
    }

    @Test
    fun failedImagesAndUnsavedLinksAreReported() {
        val result = parseAgentResult("""
            {"status":"completed","output":[
              {"type":"openrouter:image_generation","status":"failed","error":"Prompt was rejected"},
              {"type":"openrouter:image_generation","status":"completed","image_url":"https://example.com/a.png"},
              {"type":"openrouter:image_generation","status":"completed","image_url":"http://example.com/b.png"},
              {"type":"message","content":[{"type":"output_text","text":"Done."}]}
            ]}
        """.trimIndent())
        assertTrue(result.media.isEmpty())
        assertEquals("Done.\n\n[Generated image](https://example.com/a.png)", result.assistantText)
        assertEquals("The image could not be made: Prompt was rejected", result.notice)
    }

    @Test
    fun markersThatAreNotImagesAreIgnored() {
        val result = parseAgentResult("""
            {"status":"completed","output":[
              {"type":"message","content":[{"type":"output_text","text":"oratt:b64:${"2".repeat(64)}.pdf"}]}
            ]}
        """.trimIndent())
        assertTrue(result.media.isEmpty())
    }

    @Test
    fun imageSettingsReachTheRuntimeOnlyWhenToolsAreOn() {
        val model = CatalogModel("vendor/vision", "Vision", 0, setOf("tools"))
        val config = AgentConfig(apiKey = "k", model = model.id, imageGeneration = true,
            imageModel = " vendor/painter ", imageAspect = "16:9", imageQuality = "high")
        val options = runtimeOptions(config, Workspace(), "", model)
        val image = options.getJSONObject("imageGeneration")
        assertEquals("vendor/painter", image.getString("model"))
        assertEquals("16:9", image.getString("aspectRatio"))
        assertEquals("high", image.getString("quality"))
        assertFalse(runtimeOptions(config.withoutTools(), Workspace(), "", model).has("imageGeneration"))
        assertTrue(config.agentToolsEnabled())
        assertNull(config.settingsError())
        assertEquals("Choose an image aspect ratio from the list.", config.copy(imageAspect = "2:1").settingsError())
    }
}
