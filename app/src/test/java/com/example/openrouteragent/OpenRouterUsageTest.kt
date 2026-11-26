package com.example.openrouteragent

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class OpenRouterUsageTest {
    @Test fun usesSeparateCredentialsForKeyTotalsAndAccountActivity() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"data":{"label":"Phone","usage":4.25,"usage_daily":0,"usage_weekly":1.5,
                    "usage_monthly":4,"limit":null,"limit_remaining":null,"byok_usage":2.5}}"""
            ))
            server.enqueue(MockResponse().setBody("""{"data":[$activity]}"""))
            val client = OpenRouterAgentClient(server.url("/api/v1/"))

            val snapshot = client.keyUsage("chat-key")
            val rows = client.usageActivity("management-key")

            assertEquals("/api/v1/key", server.takeRequest().let {
                assertEquals("Bearer chat-key", it.getHeader("Authorization"))
                it.path
            })
            assertEquals("/api/v1/activity", server.takeRequest().let {
                assertEquals("Bearer management-key", it.getHeader("Authorization"))
                it.path
            })
            assertNull(snapshot.limit)
            assertEquals(0.0, snapshot.daily!!, 0.0)
            assertEquals(2.5, snapshot.byok!!, 0.0)
            assertEquals("OpenAI", rows.single().provider)
            assertEquals(3_000_000_000L, rows.single().totals.prompt)
            assertEquals(0.012, rows.single().totals.byok, 0.0)
        }
    }

    @Test fun activityAcceptsKeyCopiedWithBearerPrefixAndQuotes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[$activity]}"""))
            val client = OpenRouterAgentClient(server.url("/api/v1/"))
            val key = "sk-or-v1-" + "a".repeat(64)

            val rows = client.usageActivity("  \"Bearer $key\"  ")

            assertEquals("Bearer $key", server.takeRequest().getHeader("Authorization"))
            assertEquals(1, rows.size)
        }
    }

    @Test fun completeUtcDaysExcludeTodayAndFillGapsAfterFiltering() {
        val today = LocalDate.parse("2025-08-26")
        val row = parseUsageActivity("""{"data":[$activity]}""").single()
        val filtered = filterUsageActivity(
            listOf(row, row.copy(date = today), row.copy(date = today.minusDays(8)),
                row.copy(provider = "Other"), row.copy(model = "another/model")),
            today, 7, model = row.model, provider = row.provider,
        )
        val series = dailyUsage(filtered, today, 7)

        assertEquals(listOf(row), filtered)
        assertEquals(today.minusDays(7), series.first().first)
        assertEquals(today.minusDays(1), series.last().first)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 5L, 0L), series.map { it.second.requests })
        assertEquals(3_000_000_125L, series.sumOf { it.second.tokens })
    }

    @Test fun rankingsUseTheSelectedMetricAndGroupMatchingModels() {
        val row = parseUsageActivity("""{"data":[$activity]}""").single()
        val alternate = row.copy(model = "another/model", totals = row.totals.copy(cost = 10.0, requests = 1))
        val rows = listOf(row, row.copy(provider = "Other"), alternate)

        assertEquals(listOf("another/model", row.model), usageRanking(rows, UsageMetric.Cost) { it.model }.map { it.first })
        val requests = usageRanking(rows, UsageMetric.Requests) { it.model }
        assertEquals(row.model, requests.first().first)
        assertEquals(10L, requests.first().second.requests)
    }

    private val activity = """
        {"date":"2025-08-24","model":"openai/gpt-4.1","model_permaslug":"openai/gpt-4.1-2025-04-14",
        "endpoint_id":"ep-1","provider_name":"OpenAI","usage":0.015,"byok_usage_inference":0.012,
        "requests":5,"prompt_tokens":3000000000,"completion_tokens":125,"reasoning_tokens":25,"cached_tokens":10}
    """.trimIndent()
}
