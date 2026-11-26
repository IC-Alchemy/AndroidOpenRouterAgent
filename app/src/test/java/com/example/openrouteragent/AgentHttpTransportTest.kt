package com.example.openrouteragent

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Base64
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val FAKE_KEY = "sk-or-v1-test-secret"

class AgentHttpTransportTest {
    @Test
    fun chatCompletionsUsesTheFixedJsonRouteWithoutRetrying() = withTransport { server, run ->
        server.enqueue(MockResponse().setResponseCode(503))
        val outcome = run.start(AgentEndpoint.ChatCompletions)
        assertEquals("error", outcome.kind)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/api/v1/chat/completions", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("Bearer $FAKE_KEY", request.getHeader("Authorization"))
        assertEquals(OpenRouterAttribution.REFERER, request.getHeader("HTTP-Referer"))
        assertEquals(OpenRouterAttribution.TITLE, request.getHeader("X-OpenRouter-Title"))
        assertTrue(run.retries.isEmpty())
    }

    @Test
    fun openAiRunsGoToOpenAiWithoutOpenRouterHeaders() = withTransport { server, run ->
        server.enqueue(MockResponse().setResponseCode(401))
        val outcome = run.start(target = AgentTarget.OpenAi)
        assertEquals("error", outcome.kind)
        val request = server.takeRequest()
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer $FAKE_KEY", request.getHeader("Authorization"))
        assertNull(request.getHeader("HTTP-Referer"))
        assertNull(request.getHeader("X-OpenRouter-Title"))
        val message = outcome.value as String
        assertTrue(message, "OpenAI HTTP 401" in message)
        assertFalse(message, FAKE_KEY in message)
    }

    @Test
    fun keepaliveCommentsCountAsActivityAndTheStreamFinishes() = withTransport(idleSeconds = 1) { server, run ->
        val comments = ": OPENROUTER PROCESSING\n\n".repeat(6)
        val payload = "event: response.completed\ndata: {\"type\":\"response.completed\"}\n\ndata: [DONE]\n\n"
        // Six keepalives arrive 400 ms apart: 2.4 s in total, well past the 1 s idle limit.
        server.enqueue(sse(comments + payload).throttleBody(25, 400, TimeUnit.MILLISECONDS))
        val outcome = run.start()
        assertEquals("end", outcome.kind)
        assertTrue(run.body.toString().endsWith("data: [DONE]\n\n"))
        assertTrue(run.logs.any { "keepalives=6" in it })
    }

    @Test
    fun anEmptyStreamFailsAtTheIdleLimitWithDiagnostics() = withTransport(idleSeconds = 1) { server, run ->
        // Headers arrive at once; the first body byte would only arrive after 3 s, past the 1 s idle limit.
        server.enqueue(sse("data: [DONE]\n\n").setHeader("x-request-id", "req_empty")
            .setBodyDelay(3, TimeUnit.SECONDS))
        val started = System.nanoTime()
        val outcome = run.start()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals("error", outcome.kind)
        assertTrue("took $elapsed ms", elapsed < 5_000)
        val message = outcome.value as String
        assertTrue(message, "no data for 1 s" in message)
        assertTrue(message, "HTTP 200" in message)
        assertTrue(message, "0 bytes" in message)
        assertTrue(message, "req_empty" in message)
        assertTrue(message, "mock/model" in message)
    }

    @Test
    fun aStalledStreamNamesTheLastEventAndHostedTool() = withTransport(idleSeconds = 1) { server, run ->
        val first = "event: response.output_item.added\n" +
            "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"openrouter:shell\",\"id\":\"sh_1\"}}\n\n"
        server.enqueue(sse(first + "data: [DONE]\n\n").throttleBody(first.length.toLong(), 3, TimeUnit.SECONDS))
        val outcome = run.start()
        assertEquals("error", outcome.kind)
        val message = outcome.value as String
        assertTrue(message, "response.output_item.added" in message)
        assertTrue(message, "openrouter:shell" in message)
        assertTrue(message, "${first.length} bytes" in message)
        // Regression: a stall must always produce a log line before the run gives up.
        assertTrue(run.logs.joinToString("\n"), run.logs.any { it.startsWith("idle-timeout") })
    }

    @Test
    fun rateLimitIsRetriedAfterTheRetryAfterDelay() = withTransport { server, run ->
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1")
            .setBody("""{"error":{"message":"Rate limit exceeded","code":429}}"""))
        server.enqueue(sse("data: [DONE]\n\n"))
        val started = System.nanoTime()
        val outcome = run.start()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals("end", outcome.kind)
        assertEquals(2, server.requestCount)
        assertTrue("waited $elapsed ms", elapsed >= 900)
        assertTrue(run.retries.single(), "HTTP 429" in run.retries.single())
    }

    @Test
    fun serverErrorsAreRetriedWithBackoffThenReported() = withTransport { server, run ->
        repeat(4) {
            server.enqueue(MockResponse().setResponseCode(500)
                .setBody("""{"error":{"message":"Internal Server Error","code":500}}"""))
        }
        val outcome = run.start()
        assertEquals("error", outcome.kind)
        assertEquals(4, server.requestCount)
        assertEquals(3, run.retries.size)
        val message = outcome.value as String
        assertTrue(message, "HTTP 500" in message)
        assertTrue(message, "3 retries" in message)
        assertTrue(message, "Internal Server Error" in message)
    }

    @Test
    fun aConnectionResetBeforeHeadersIsRetried() = withTransport { server, run ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(sse("data: [DONE]\n\n"))
        val outcome = run.start()
        assertEquals("${outcome.value} · retries=${run.retries} · logs=${run.logs}", "end", outcome.kind)
        assertEquals(2, server.requestCount)
        assertEquals(1, run.retries.size)
    }

    @Test
    fun aMidStreamDisconnectDuringReasoningSaysItCanBeSentAgain() = withTransport { server, run ->
        server.enqueue(sse("event: response.created\ndata: {\"type\":\"response.created\"}\n\n" +
            "data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\"}}\n\n" +
            "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"thinking\"}\n\n" + "x".repeat(4096))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val outcome = run.start()
        assertEquals("error", outcome.kind)
        // The transport itself never resends after headers: the SDK has already read part of the stream.
        assertEquals(1, server.requestCount)
        val message = outcome.value as String
        assertTrue(message, "closed unexpectedly" in message)
        assertTrue(message, STREAM_DROP_SAFE_TO_RESEND in message)
        assertTrue(message, "response.reasoning_text.delta" in message)
        assertTrue(message, "Phone: screen off, app in the background." in message)
    }

    @Test
    fun aMidStreamDisconnectAfterAHostedToolStartedIsNotMarkedSafe() = withTransport { server, run ->
        server.enqueue(sse("data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"ws_1\",\"type\":\"web_search_call\"}}\n\n" +
            "data: {\"type\":\"response.web_search_call.searching\"}\n\n" + "x".repeat(4096))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val outcome = run.start()
        assertEquals("error", outcome.kind)
        assertEquals(1, server.requestCount)
        val message = outcome.value as String
        assertTrue(message, "A hosted tool had started" in message)
        assertFalse(message, STREAM_DROP_SAFE_TO_RESEND in message)
    }

    @Test
    fun aHostedToolEventAloneMarksTheResponseUnsafe() {
        for (event in listOf("response.web_search_call.searching", "response.code_interpreter_call.in_progress",
            "response.image_generation_call.generating", "response.mcp_call_arguments.delta")) {
            val diagnostics = StreamDiagnostics("run/1", "model", 90)
            val line = "data: {\"type\":\"$event\"}\n".toByteArray()
            diagnostics.received(line, line.size)
            assertTrue(event, diagnostics.toolStarted)
        }
        val words = StreamDiagnostics("run/2", "model", 90)
        val text = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n".toByteArray()
        words.received(text, text.size)
        assertFalse(words.toolStarted)
    }

    @Test
    fun theAppsOwnToolCallsDontMarkTheResponseUnsafe() {
        // The SDK runs the app's own calls only after the whole response arrives, so a drop mid-call ran nothing.
        val diagnostics = StreamDiagnostics("run/1", "model", 90)
        val lines = ("data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"name\":\"write_file\"}}\n" +
            "data: {\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{\"}\n" +
            "data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"ct_1\",\"type\":\"custom_tool_call\"}}\n" +
            "data: {\"type\":\"response.custom_tool_call_input.delta\",\"delta\":\"x\"}\n").toByteArray()
        diagnostics.received(lines, lines.size)
        assertFalse(diagnostics.toolStarted)
        // A hosted item in the same response still counts.
        val hosted = "data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"ci_1\",\"type\":\"code_interpreter_call\"}}\n".toByteArray()
        diagnostics.received(hosted, hosted.size)
        assertTrue(diagnostics.toolStarted)
    }

    @Test
    fun aMidStreamDisconnectWhileTheAppsCallStreamsSaysItCanBeSentAgain() = withTransport { server, run ->
        server.enqueue(sse("data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\"}}\n\n" +
            "data: {\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{\\\"path\"}\n\n" + "x".repeat(4096))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val outcome = run.start()
        assertEquals("error", outcome.kind)
        val message = outcome.value as String
        assertTrue(message, STREAM_DROP_SAFE_TO_RESEND in message)
        assertTrue(message, "response.function_call_arguments.delta" in message)
    }


    @Test
    fun anInvalidApiKeyFailsFastWithoutRetryOrLeakingTheKey() = withTransport { server, run ->
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"error":{"message":"No auth credentials found","code":401}}"""))
        val outcome = run.start()
        assertEquals("error", outcome.kind)
        assertEquals(1, server.requestCount)
        assertEquals("Bearer $FAKE_KEY", server.takeRequest().getHeader("Authorization"))
        val message = outcome.value as String
        assertTrue(message, "HTTP 401" in message && "API key" in message && "No auth credentials found" in message)
        assertFalse(run.logs.any { FAKE_KEY in it })
        assertFalse(FAKE_KEY in message)
    }

    @Test
    fun anInvalidModelFailsFastAndNamesTheModel() = withTransport { server, run ->
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"error":{"message":"mock/model is not a valid model ID","code":400}}"""))
        val outcome = run.start()
        assertEquals(1, server.requestCount)
        val message = outcome.value as String
        assertTrue(message, "HTTP 400" in message && "model" in message && "Models" in message)
    }

    @Test
    fun creditAndPermissionErrorsAreNotRetried() = withTransport { server, run ->
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":{"message":"Insufficient credits"}}"""))
        val message = run.start().value as String
        assertEquals(1, server.requestCount)
        assertTrue(message, "HTTP 402" in message && "credits" in message)
    }

    @Test
    fun aLongRetryAfterIsReportedInsteadOfBlocking() = withTransport { server, run ->
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3600")
            .setBody("""{"error":{"message":"Daily limit reached"}}"""))
        val outcome = run.start()
        assertEquals(1, server.requestCount)
        val message = outcome.value as String
        assertTrue(message, "HTTP 429" in message && "3600 s" in message)
    }

    @Test
    fun retryDelaysBackOffWithJitterAndHonourRetryAfter() {
        val policy = StreamPolicy(baseDelayMillis = 1_000, maxDelayMillis = 20_000, maxRetryAfterMillis = 60_000)
        assertEquals(1_000L, retryDelayMillis(0, null, policy, random = { 1.0 }))
        assertEquals(4_000L, retryDelayMillis(2, null, policy, random = { 1.0 }))
        assertEquals(20_000L, retryDelayMillis(9, null, policy, random = { 1.0 }))
        assertEquals(500L, retryDelayMillis(0, null, policy, random = { 0.5 }))
        assertEquals(7_000L, retryDelayMillis(0, "7", policy, random = { 0.0 }))
        assertNull(retryDelayMillis(0, "3600", policy, random = { 0.0 }))
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(5))
        val parsed = retryDelayMillis(0, date, policy, random = { 0.0 })!!
        assertTrue("$parsed", parsed in 3_000..6_000)
    }

    private class Outcome(val kind: String, val value: Any)

    private class Run(
        private val transport: AgentHttpTransport, private val idleSeconds: Long?, val logs: List<String>,
    ) {
        val retries: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val body = StringBuilder()
        private val outcomes = LinkedBlockingQueue<Outcome>()

        fun start(route: AgentEndpoint = AgentEndpoint.Responses, target: AgentTarget = AgentTarget.OpenRouter): Outcome {
            transport.start("run", "1", FAKE_KEY, """{"model":"mock/model","input":[]}""", model = "mock/model",
                idleSeconds = idleSeconds, route = route, target = target) { kind, value ->
                when (kind) {
                    // The WebView pulls the next chunk after each delivery, exactly like this.
                    "headers" -> transport.read("run", "1")
                    "chunk" -> {
                        synchronized(body) { body.append(String(Base64.getDecoder().decode(value as String))) }
                        transport.read("run", "1")
                    }
                    "retry" -> retries += value as String
                    else -> outcomes.put(Outcome(kind, value))
                }
            }
            return outcomes.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("The transport never finished.")
        }
    }

    private fun withTransport(idleSeconds: Long? = null, block: (MockWebServer, Run) -> Unit) {
        // Bind and address the server by IP literal. "localhost" can also resolve to ::1, and after a
        // failed connection OkHttp tries the next address, where nothing is listening.
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = MockWebServer()
        server.start(loopback, 0)
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val transport = AgentHttpTransport(
            endpoint = server.url("/api/v1/responses").newBuilder().host(loopback.hostAddress).build(),
            openAiEndpoint = server.url("/v1/responses").newBuilder().host(loopback.hostAddress).build(),
            policy = StreamPolicy(connectTimeoutSeconds = 5, baseDelayMillis = 20, maxDelayMillis = 100),
            encode = { bytes, size -> Base64.getEncoder().encodeToString(bytes.copyOf(size)) },
            log = { logs += it },
            environment = { "screen off, app in the background" },
        )
        try {
            block(server, Run(transport, idleSeconds, logs))
        } finally {
            transport.cancelRun("run")
            server.shutdown()
        }
    }

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
}
