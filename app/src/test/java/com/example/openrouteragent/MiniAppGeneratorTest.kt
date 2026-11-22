package com.example.openrouteragent

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MiniAppGeneratorTest {
    @get:Rule val folder = TemporaryFolder()

    private val validSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" stroke="#FFFFFF" fill="none"/></svg>"""
    private val changedSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" stroke="#FFFFFF" fill="none"/></svg>"""

    private fun completionResponse(content: String): MockResponse {
        val body = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put("role", "assistant").put("content", content),
                ),
            ),
        )
        return MockResponse().setResponseCode(200).setBody(body.toString())
    }

    @Test
    fun createMiniAppUsesStrictJsonSchemaSanitizesAndSavesBundle() = runTest {
        MockWebServer().use { server ->
            val payloadJson = JSONObject()
                .put("name", "Pomodoro Focus")
                .put("themeColor", "bad-color")
                .put("iconSvg", validSvg)
                .put("htmlCode", "<html><head></head><body><h1>25:00</h1></body></html>")
                .toString()
            server.enqueue(completionResponse(payloadJson))

            val rendered = mutableListOf<Triple<String?, String, String>>()
            val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9)
            val generator = MiniAppGenerator(
                openRouterBaseUrl = server.url("/api/v1/"),
                renderIcon = { svg, color, name ->
                    rendered += Triple(svg, color, name)
                    fakePng
                },
            )
            val repo = MiniAppRepository(folder.newFolder("apps"), InMemoryMiniAppIndex())
            val config = AgentConfig(apiKey = "sk-test-123")
            val model = CatalogModel("openai/gpt-4o", "GPT-4o", 128_000, setOf("structured_outputs", "response_format"))

            val app = generator.createMiniApp(config, model, repo, "Make me a Pomodoro timer")

            val req = server.takeRequest()
            assertEquals("/api/v1/chat/completions", req.path)
            val reqJson = JSONObject(req.body.readUtf8())
            val responseFormat = reqJson.getJSONObject("response_format")
            assertEquals("json_schema", responseFormat.getString("type"))
            val schemaWrapper = responseFormat.getJSONObject("json_schema")
            assertTrue(schemaWrapper.getBoolean("strict"))
            val required = schemaWrapper.getJSONObject("schema").getJSONArray("required")
            assertEquals(listOf("name", "themeColor", "iconSvg", "htmlCode"), List(required.length()) { required.getString(it) })

            assertEquals("Pomodoro Focus", app.name)
            // Malformed hex color fell back safely.
            assertEquals(MINI_APP_DEFAULT_THEME_COLOR, app.themeColor)
            assertEquals(ICON_URI_FILE, app.iconUri)
            assertEquals(1, rendered.size)
            assertArrayEquals(fakePng, repo.iconFile(app.id)!!.readBytes())
            val savedHtml = repo.html(app.id)!!
            assertTrue(savedHtml.contains("name=\"viewport\""))
            assertTrue(savedHtml.contains("<title>Pomodoro Focus</title>"))
        }
    }

    @Test
    fun createMiniAppFallsBackWhenEndpointRejectsJsonSchema() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"json_schema unsupported"}}"""))
            val payloadJson = JSONObject()
                .put("name", "Tip Split")
                .put("themeColor", "#16a34a")
                .put("iconSvg", validSvg)
                .put("htmlCode", "<!DOCTYPE html><html><head><title>Tip Split</title></head><body>Hi</body></html>")
                .toString()
            server.enqueue(completionResponse("```json\n$payloadJson\n```"))

            val generator = MiniAppGenerator(
                openRouterBaseUrl = server.url("/api/v1/"),
                renderIcon = { _, _, _ -> byteArrayOf(1, 2, 3) },
            )
            val repo = MiniAppRepository(folder.newFolder("fallback"), InMemoryMiniAppIndex())
            val config = AgentConfig(apiKey = "sk-test-123")
            val model = CatalogModel("vendor/model", "Model", 64_000, setOf("structured_outputs", "response_format"))

            val app = generator.createMiniApp(config, model, repo, "Tip splitter")
            assertEquals("Tip Split", app.name)
            assertEquals("#16A34A", app.themeColor)
            assertEquals(2, server.requestCount)
            val firstReq = JSONObject(server.takeRequest().body.readUtf8())
            val secondReq = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("json_schema", firstReq.getJSONObject("response_format").getString("type"))
            assertEquals("json_object", secondReq.getJSONObject("response_format").getString("type"))
        }
    }

    @Test
    fun conversationalEditPreservesUuidAndOnlyRegeneratesIconWhenColorOrSvgChanges() = runTest {
        MockWebServer().use { server ->
            val repo = MiniAppRepository(folder.newFolder("edits"), InMemoryMiniAppIndex())
            val originalPng = byteArrayOf(10, 20, 30, 40)
            val updatedPng = byteArrayOf(99, 98, 97, 96)
            val existing = repo.save(
                name = "Focus Timer",
                html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Focus Timer</title></head><body><button>Start</button></body></html>",
                themeColor = "#2563EB",
                iconUpdate = MiniAppIconUpdate.PngBytes(originalPng, iconSvg = validSvg),
            )

            // Edit 1: only HTML changes ("make the buttons bigger"); themeColor & iconSvg unchanged.
            server.enqueue(
                completionResponse(
                    JSONObject()
                        .put("name", "Focus Timer")
                        .put("themeColor", "#2563EB")
                        .put("iconSvg", validSvg)
                        .put("htmlCode", "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Focus Timer</title></head><body><button style=\"height:64px\">Start</button></body></html>")
                        .toString(),
                ),
            )
            // Edit 2: themeColor and iconSvg change ("switch to dark emerald theme").
            server.enqueue(
                completionResponse(
                    JSONObject()
                        .put("name", "Focus Timer")
                        .put("themeColor", "#0D9488")
                        .put("iconSvg", changedSvg)
                        .put("htmlCode", "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Focus Timer</title></head><body style=\"background:#111\">Dark</body></html>")
                        .toString(),
                ),
            )

            var renderCount = 0
            val generator = MiniAppGenerator(
                openRouterBaseUrl = server.url("/api/v1/"),
                renderIcon = { _, _, _ ->
                    renderCount++
                    updatedPng
                },
            )
            val config = AgentConfig(apiKey = "sk-test-123")
            val model = CatalogModel("openai/gpt-4o", "GPT-4o", 128_000, setOf("structured_outputs"))

            val edited1 = generator.editMiniApp(config, model, repo, existing.id, "Make the buttons bigger")
            assertEquals(existing.id, edited1.id)
            assertEquals(2, edited1.version)
            assertEquals(0, renderCount)
            assertArrayEquals(originalPng, repo.iconFile(existing.id)!!.readBytes())
            assertTrue(repo.html(existing.id)!!.contains("height:64px"))

            val firstPrompt = JSONObject(server.takeRequest().body.readUtf8())
                .getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(firstPrompt.contains("<button>Start</button>"))
            assertTrue(firstPrompt.contains("Make the buttons bigger"))

            val edited2 = generator.editMiniApp(config, model, repo, existing.id, "Change theme to dark teal")
            assertEquals(existing.id, edited2.id)
            assertEquals(3, edited2.version)
            assertEquals("#0D9488", edited2.themeColor)
            assertEquals(1, renderCount)
            assertArrayEquals(updatedPng, repo.iconFile(existing.id)!!.readBytes())
            assertNotNull(repo.manifest(existing.id)?.iconSvg)
        }
    }

    @Test
    fun conversationalEditPreservesCustomUserEmojiOrUploadedPhotoIcon() = runTest {
        MockWebServer().use { server ->
            val repo = MiniAppRepository(folder.newFolder("custom_icon_edits"), InMemoryMiniAppIndex())
            val existing = repo.save(
                name = "Mood Log",
                html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Mood Log</title></head><body>v1</body></html>",
                themeColor = "#2563EB",
                iconUpdate = MiniAppIconUpdate.Emoji("🌈"),
            )
            server.enqueue(
                completionResponse(
                    JSONObject()
                        .put("name", "Mood Log")
                        .put("themeColor", "#7C3AED")
                        .put("iconSvg", validSvg)
                        .put("htmlCode", "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Mood Log</title></head><body>v2</body></html>")
                        .toString(),
                ),
            )
            var renderCount = 0
            val generator = MiniAppGenerator(
                openRouterBaseUrl = server.url("/api/v1/"),
                renderIcon = { _, _, _ ->
                    renderCount++
                    byteArrayOf(1, 2, 3)
                },
            )
            val config = AgentConfig(apiKey = "sk-test-123")
            val model = CatalogModel("openai/gpt-4o", "GPT-4o", 128_000, setOf("structured_outputs"))

            val edited = generator.editMiniApp(config, model, repo, existing.id, "Add dark mode")
            assertEquals(existing.id, edited.id)
            assertEquals("emoji:🌈", edited.iconUri)
            assertEquals(0, renderCount)

            // Also preserve a user-uploaded gallery photo (aiGenerated = false, iconSvg = null).
            val uploadedPhoto = byteArrayOf(42, 43, 44, 45)
            val photoApp = repo.save(
                name = "Pet Log",
                html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Pet Log</title></head><body>v1</body></html>",
                themeColor = "#2563EB",
                iconUpdate = MiniAppIconUpdate.PngBytes(uploadedPhoto, iconSvg = null, aiGenerated = false),
            )
            assertFalse(repo.manifest(photoApp.id)!!.aiGeneratedIcon)
            server.enqueue(
                completionResponse(
                    JSONObject()
                        .put("name", "Pet Log")
                        .put("themeColor", "#DC2626")
                        .put("iconSvg", validSvg)
                        .put("htmlCode", "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>Pet Log</title></head><body>v2</body></html>")
                        .toString(),
                ),
            )
            val editedPhoto = generator.editMiniApp(config, model, repo, photoApp.id, "Use red theme")
            assertEquals(photoApp.id, editedPhoto.id)
            assertEquals("#DC2626", editedPhoto.themeColor)
            assertEquals(0, renderCount)
            assertArrayEquals(uploadedPhoto, repo.iconFile(photoApp.id)!!.readBytes())
            assertFalse(repo.manifest(photoApp.id)!!.aiGeneratedIcon)
        }
    }

    @Test
    fun conversationalEditRegeneratesAiFallbackIconWhenColorOrSvgChanges() = runTest {
        MockWebServer().use { server ->
            // Initial creation returns a malicious SVG that fails sanitization -> fallback initial PNG with aiGeneratedIcon = true.
            server.enqueue(
                completionResponse(
                    JSONObject()
                        .put("name", "Water Log")
                        .put("themeColor", "#2563EB")
                        .put("iconSvg", "<svg><script>alert(1)</script></svg>")
                        .put("htmlCode", "<!DOCTYPE html><html><body>v1</body></html>")
                        .toString(),
                ),
            )
            // Edit 1: only HTML changes -> should NOT re-render fallback icon.
            server.enqueue(
                completionResponse(
                    JSONObject()
                        .put("name", "Water Log")
                        .put("themeColor", "#2563EB")
                        .put("iconSvg", "")
                        .put("htmlCode", "<!DOCTYPE html><html><body>v2</body></html>")
                        .toString(),
                ),
            )
            // Edit 2: rename changing first initial ("Water Log" -> "Hydration", same color, no iconSvg) -> MUST regenerate initial PNG.
            server.enqueue(
                completionResponse(
                    JSONObject()
                        .put("name", "Hydration")
                        .put("themeColor", "#2563EB")
                        .put("iconSvg", "")
                        .put("htmlCode", "<!DOCTYPE html><html><body>v2b</body></html>")
                        .toString(),
                ),
            )
            // Edit 3: themeColor changes (still no valid iconSvg) -> MUST re-render AI fallback icon with new color.
            server.enqueue(
                completionResponse(
                    JSONObject()
                        .put("name", "Hydration")
                        .put("themeColor", "#16A34A")
                        .put("iconSvg", "")
                        .put("htmlCode", "<!DOCTYPE html><html><body>v3</body></html>")
                        .toString(),
                ),
            )
            // Edit 4: LLM now supplies a valid iconSvg -> MUST render new SVG icon.
            server.enqueue(
                completionResponse(
                    JSONObject()
                        .put("name", "Hydration")
                        .put("themeColor", "#16A34A")
                        .put("iconSvg", validSvg)
                        .put("htmlCode", "<!DOCTYPE html><html><body>v4</body></html>")
                        .toString(),
                ),
            )

            val renderedCalls = mutableListOf<Triple<String?, String, String>>()
            val generator = MiniAppGenerator(
                openRouterBaseUrl = server.url("/api/v1/"),
                renderIcon = { svg, color, name ->
                    renderedCalls += Triple(svg, color, name)
                    "png:${renderedCalls.size}:$color:$name".toByteArray()
                },
            )
            val repo = MiniAppRepository(folder.newFolder("ai_fallback_edit"), InMemoryMiniAppIndex())
            val config = AgentConfig(apiKey = "sk-test-123")
            val model = CatalogModel("openai/gpt-4o", "GPT-4o", 128_000, setOf("structured_outputs"))

            val created = generator.createMiniApp(config, model, repo, "Track daily water intake")
            val initialManifest = repo.manifest(created.id)!!
            assertNull(initialManifest.iconSvg)
            assertTrue(initialManifest.aiGeneratedIcon)
            assertEquals(1, renderedCalls.size)

            generator.editMiniApp(config, model, repo, created.id, "Make font bigger")
            assertEquals(1, renderedCalls.size)

            generator.editMiniApp(config, model, repo, created.id, "Rename to Hydration")
            assertEquals(2, renderedCalls.size)
            assertEquals(Triple(null, "#2563EB", "Hydration"), renderedCalls[1])
            assertTrue(repo.manifest(created.id)!!.aiGeneratedIcon)
            assertEquals("png:2:#2563EB:Hydration", repo.iconFile(created.id)!!.readText())

            generator.editMiniApp(config, model, repo, created.id, "Change theme to green")
            assertEquals(3, renderedCalls.size)
            assertEquals(Triple(null, "#16A34A", "Hydration"), renderedCalls[2])
            assertTrue(repo.manifest(created.id)!!.aiGeneratedIcon)
            assertEquals("png:3:#16A34A:Hydration", repo.iconFile(created.id)!!.readText())

            generator.editMiniApp(config, model, repo, created.id, "Give it a droplet icon")
            assertEquals(4, renderedCalls.size)
            assertNotNull(renderedCalls[3].first)
            assertNotNull(repo.manifest(created.id)!!.iconSvg)
            assertTrue(repo.manifest(created.id)!!.aiGeneratedIcon)
            assertEquals("png:4:#16A34A:Hydration", repo.iconFile(created.id)!!.readText())
        }
    }

    @Test
    fun createMiniAppTriesJsonSchemaWhenModelParametersEmptyAndFallsBackOn404ParameterError() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(404)
                    .setBody("""{"error":{"message":"No endpoints found that can handle the requested parameters."}}"""),
            )
            // Fallback model omits iconSvg; deserialization should succeed and render fallback icon.
            val fallbackPayload = JSONObject()
                .put("name", "Unit Flip")
                .put("themeColor", "#4F46E5")
                .put("htmlCode", "<!DOCTYPE html><html><head><title>Unit Flip</title></head><body>ok</body></html>")
                .toString()
            server.enqueue(completionResponse(fallbackPayload))

            val generator = MiniAppGenerator(
                openRouterBaseUrl = server.url("/api/v1/"),
                renderIcon = { _, _, _ -> byteArrayOf(7, 8, 9) },
            )
            val repo = MiniAppRepository(folder.newFolder("empty_params"), InMemoryMiniAppIndex())
            val config = AgentConfig(apiKey = "sk-test-123")
            val manualModel = CatalogModel("custom/typed-model", "Typed Model", 0, emptySet())

            val app = generator.createMiniApp(config, manualModel, repo, "Make a unit converter")
            assertEquals("Unit Flip", app.name)
            assertEquals("#4F46E5", app.themeColor)
            assertEquals(2, server.requestCount)
            val firstReq = JSONObject(server.takeRequest().body.readUtf8())
            val secondReq = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("json_schema", firstReq.getJSONObject("response_format").getString("type"))
            assertEquals("json_object", secondReq.getJSONObject("response_format").getString("type"))
        }
    }
}
