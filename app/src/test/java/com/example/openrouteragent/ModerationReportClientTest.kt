package com.example.openrouteragent

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationReportClientTest {
    @Test
    fun submitsStrictThreeFieldPayloadWithoutAuthOrExtraMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val client = ModerationReportClient(
                http = OkHttpClient(),
                endpointUrl = server.url("/webhook/moderation-reports").toString(),
            )

            val result = client.submitReport(
                prompt = "User prompt text",
                response = "Flagged assistant reply",
                reason = "Harassment",
            )

            assertEquals(ReportResult.Sent, result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/webhook/moderation-reports", request.path)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-User-Id"))
            assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))

            val json = JSONObject(request.body.readUtf8())
            val keys = json.keys().asSequence().toSet()
            assertEquals(setOf("prompt", "response", "reason"), keys)
            assertEquals("User prompt text", json.getString("prompt"))
            assertEquals("Flagged assistant reply", json.getString("response"))
            assertEquals("Harassment", json.getString("reason"))
        }
    }

    @Test
    fun handlesBlankOrUnconfiguredEndpointGracefullyWithoutCrashing() = runBlocking {
        assertEquals(
            ReportResult.Unconfigured,
            ModerationReportClient(endpointUrl = "").submitReport("p", "r", "Other"),
        )
        assertEquals(
            ReportResult.Unconfigured,
            ModerationReportClient(endpointUrl = "   ").submitReport("p", "r", "Other"),
        )
        assertEquals(
            ReportResult.Unconfigured,
            ModerationReportClient(endpointUrl = null).submitReport("p", "r", "Other"),
        )
    }

    @Test
    fun rejectsCleartextNonLoopbackAndMalformedEndpointsGracefully() = runBlocking {
        assertEquals(
            ReportResult.Unconfigured,
            ModerationReportClient(endpointUrl = "http://example.com/report").submitReport("p", "r", "Other"),
        )
        assertEquals(
            ReportResult.Unconfigured,
            ModerationReportClient(endpointUrl = "not-a-valid-url").submitReport("p", "r", "Other"),
        )
        assertEquals(
            ReportResult.Unconfigured,
            ModerationReportClient(endpointUrl = "https://user:pass@example.com/report").submitReport("p", "r", "Other"),
        )
    }

    @Test
    fun returnsFailedResultOnServerErrorWithoutThrowing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            val client = ModerationReportClient(
                http = OkHttpClient(),
                endpointUrl = server.url("/report").toString(),
            )

            val result = client.submitReport("p", "r", "Sexual content")
            assertTrue(result is ReportResult.Failed)
        }
    }
}
