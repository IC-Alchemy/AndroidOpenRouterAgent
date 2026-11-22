package com.example.openrouteragent

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AgentStreamingTest {
    @Test
    fun nativeTransportDeliversHeadersAndIncrementalBytes() {
        MockWebServer().use { server ->
            val body = "data: hello\n\ndata: done\n\n"
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body, 5)
                .throttleBody(5, 150, TimeUnit.MILLISECONDS))
            val events = LinkedBlockingQueue<Pair<String, Any>>()
            val transport = AgentHttpTransport(server.url("/api/v1/responses"),
                encode = { bytes, length -> Base64.getEncoder().encodeToString(bytes.copyOf(length)) })
            transport.start("run", "call", "test-key", """{"stream":true}""") { kind, value ->
                events.add(kind to value)
            }
            val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("/api/v1/responses", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            assertEquals("""{"stream":true}""", request.body.readUtf8())
            val (kind, headers) = checkNotNull(events.poll(3, TimeUnit.SECONDS))
            assertEquals("headers", kind)
            assertEquals("text/event-stream", (headers as JSONObject).getString("contentType"))
            assertNull(events.poll(50, TimeUnit.MILLISECONDS))
            val output = ByteArrayOutputStream()
            var chunks = 0
            while (true) {
                transport.read("run", "call")
                val (event, value) = checkNotNull(events.poll(3, TimeUnit.SECONDS))
                if (event == "end") break
                assertEquals("chunk", event)
                chunks++
                output.write(Base64.getDecoder().decode(value as String))
            }
            assertTrue(chunks > 1)
            assertEquals(body, output.toString(Charsets.UTF_8.name()))
        }
    }

    @Test
    fun liveTimelineKeepsThinkingToolsAndAnswerInStreamOrder() {
        val progress = AgentProgress(1000).apply(parseRunUpdates(JSONArray("""
            [
              {"kind":"reasoning","text":"Let me "}, {"kind":"reasoning","text":"check."},
              {"kind":"tool","id":"call_1","name":"web search","status":"running","detail":"{\"q\":"},
              {"kind":"tool","id":"call_1","name":"web search","status":"completed","detail":"done"},
              {"kind":"reasoning","text":"\n\nFound it."},
              {"kind":"text","text":"The answer"}, {"kind":"text","text":" is 4."}
            ]
        """.trimIndent())), 3000)
        assertEquals(listOf(
            LiveBlock.Thinking("Let me check."),
            LiveBlock.ToolCall("call_1"),
            LiveBlock.Thinking("Found it."),
            LiveBlock.Answer("The answer is 4."),
        ), progress.timeline)
        assertEquals("completed", progress.tool("call_1")?.status)
        assertEquals("Let me check.\n\nFound it.", progress.summary)
        assertEquals(3000L, progress.firstOutputAt)
        assertEquals(progress.streamedChars / 4, progress.outputTokens.toLong())
        assertNull(progress.tokensPerSecond(3500))
        assertTrue(progress.tokensPerSecond(5000)!! > 0)
    }

    @Test
    fun liveProgressKeepsPartialTextAndDeduplicatesToolsSourcesAndFiles() {
        val progress = AgentProgress(1000).apply(parseRunUpdates(JSONArray("""
            [
              {"kind":"phase","phase":"requesting","step":2},
              {"kind":"text","text":"First "}, {"kind":"text","text":"answer."},
              {"kind":"reasoning","text":"Summary"},
              {"kind":"tool","id":"call","name":"shell","status":"running"},
              {"kind":"tool","id":"call","name":"shell","status":"completed"},
              {"kind":"usage","inputTokens":7,"outputTokens":4,"cachedTokens":3,"cost":0.001},
              {"kind":"source","url":"https://example.com","title":"Source"},
              {"kind":"source","url":"https://example.com","title":"Source again"},
              {"kind":"source","url":"file:///secrets","title":"Invalid"},
              {"kind":"files","containerId":"sess","files":[{"id":"file","name":"answer.txt"}]},
              {"kind":"files","containerId":"sess","files":[{"id":"file","name":"answer.txt"}]},
              {"kind":"pulse"}
            ]
        """.trimIndent())), 2000)
        assertEquals("First answer.", progress.answer)
        assertEquals("Summary", progress.summary)
        assertEquals("completed", progress.tools.single().status)
        assertEquals(2, progress.step)
        assertEquals(2000L, progress.lastEvent)
        assertEquals(11, progress.usage?.totalTokens)
        assertEquals(3, progress.usage?.cachedTokens)
        assertEquals("Source", progress.sources.single().title)
        assertEquals("answer.txt", progress.files.single().file.path)
    }
}
