package com.example.openrouteragent

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class OpenUncensoredImagesTest {
    private val profile = openUncensoredProvider().copy(apiKey = "fake-image-key")
    private val imageModel = OpenUncensoredImageModel("z-image-turbo", "Z-Image Turbo", 0.01, promptLimit = 1_500)

    @Test
    fun imageCatalogIsPublicAndGivesPricesForGenerationModels() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""
            {"data":[
              {"id":"z-image-turbo","name":"Z-Image Turbo","operations":["generate"],"price_per_image_usd":0.01,"providers":["venice"]},
              {"id":"edit-only","name":"Editor","operations":["edit"],"price_per_image_usd":0.04},
              {"id":"both","operations":["generate","edit"],"price_per_image_usd":0.013}
            ]}
        """.trimIndent()))

        val models = client.imageModels(profile)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/images/models", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals(listOf("z-image-turbo", "both"), models.map { it.id })
        assertEquals("Z-Image Turbo", models.first().name)
        assertEquals(1_500, models.first().promptLimit)
        assertEquals(8_000, models.last().promptLimit)
        assertEquals(0.013, models.last().priceUsd)
    }

    @Test
    fun generationSendsImageSettingsAndReturnsDeliveredImagesAndCharge() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""
            {"data":[{"b64_json":"AQID"},{"url":"data:image/webp;base64,BAUG"}],
             "ou":{"model":"z-image-turbo","cost_usd":0.02,"images_failed":1}}
        """.trimIndent()))

        val result = client.generateImages(profile, ImageGenerationRequest(
            model = imageModel, prompt = "A fox in fresh snow", size = "1536x1024",
            count = 3, negativePrompt = "blur",
        ))
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals("POST", request.method)
        assertEquals("/v1/images/generations", request.path)
        assertEquals("Bearer fake-image-key", request.getHeader("Authorization"))
        assertEquals("z-image-turbo", body.getString("model"))
        assertEquals("A fox in fresh snow", body.getString("prompt"))
        assertEquals("1536x1024", body.getString("size"))
        assertEquals(3, body.getInt("n"))
        assertEquals("blur", body.getString("negative_prompt"))
        assertEquals("b64_json", body.getString("response_format"))
        assertArrayEquals(byteArrayOf(1, 2, 3), result.images[0])
        assertArrayEquals(byteArrayOf(4, 5, 6), result.images[1])
        assertEquals(1, result.failedCount)
        assertEquals(0.02, result.costUsd)
    }

    @Test
    fun imageResponsesCanExceedTheTextResponseLimit() = withServer { server, client ->
        val bytes = ByteArray(1_600_000) { 1 }
        val base64 = Base64.getEncoder().encodeToString(bytes)
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"$base64"}]}"""))
        val result = client.generateImages(profile, ImageGenerationRequest(model = imageModel, prompt = "A fox"))
        assertArrayEquals(bytes, result.images.single())
    }

    @Test
    fun generationRequiresAKeyAndNeverDownloadsAHostedUrl() = withServer { server, client ->
        val settings = ImageGenerationRequest(model = imageModel, prompt = "A fox")
        val missingKey = runCatching { client.generateImages(profile.copy(apiKey = ""), settings) }.exceptionOrNull()
        assertTrue(missingKey is IllegalArgumentException)
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse().setBody("""{"data":[{"url":"https://example.com/image.png"}]}"""))
        val unusable = runCatching { client.generateImages(profile, settings) }.exceptionOrNull()
        assertTrue(unusable is IOException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun invalidSettingsAreRejectedBeforeSubmittingAPaidRequest() = withServer { server, client ->
        val settings = ImageGenerationRequest(model = imageModel, prompt = "A fox")
        val invalid = listOf(settings.copy(prompt = " "), settings.copy(prompt = "a".repeat(1_501)),
            settings.copy(count = 5), settings.copy(size = "800x800"))
        for (request in invalid) {
            val error = runCatching { client.generateImages(profile, request) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun generationRedactsTheKeyFromProviderErrors() = withServer { server, client ->
        server.enqueue(MockResponse().setResponseCode(402)
            .setBody("""{"error":{"message":"Add credits to fake-image-key"}}"""))
        val error = runCatching {
            client.generateImages(profile, ImageGenerationRequest(model = imageModel, prompt = "A fox"))
        }.exceptionOrNull()
        assertEquals("OpenUncensored HTTP 402. Add credits to [API key]", error?.message)
    }

    private fun withServer(test: suspend (MockWebServer, CompatibleChatClient) -> Unit) {
        MockWebServer().use { server ->
            val client = CompatibleChatClient(base = { server.url("/v1/") })
            runBlocking { test(server, client) }
        }
    }
}
