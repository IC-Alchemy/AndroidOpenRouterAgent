package com.example.openrouteragent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class SideConversationTest {
    private fun user(text: String) = SideTextMessage(true, text)
    private fun assistant(text: String) = SideTextMessage(false, text)

    @Test
    fun mainChatContextIsATranscriptWithTheWaitingMessage() {
        val context = mainChatContext(listOf(user("Plan a trip"), assistant("Where to?"), user(" ")), "Lisbon")
        assertNotNull(context)
        assertEquals("User: Plan a trip\n\nAssistant: Where to?\n\n" +
            "User (still waiting for the main chat's reply): Lisbon", context!!.text)
        assertEquals(3, context.included)
        assertEquals(0, context.omitted)
    }

    @Test
    fun emptyMainChatGivesNoContext() {
        assertNull(mainChatContext(emptyList(), null))
        assertNull(mainChatContext(listOf(user("  ")), " "))
    }

    @Test
    fun longMainChatKeepsTheNewestMessages() {
        val messages = (1..10).map { user("message $it ".repeat(10)) }
        val context = mainChatContext(messages, null, maxChars = 400)!!
        assertTrue(context.omitted > 0)
        assertEquals(10, context.included + context.omitted)
        assertTrue(context.text.startsWith("[${context.omitted} earlier main chat messages left out]"))
        assertTrue(context.text.endsWith("message 10 ".repeat(10).trim()))
        assertFalse(context.text.contains("message 1 "))
    }

    @Test
    fun oneHugeMessageIsClippedInTheMiddle() {
        val text = "a".repeat(5_000) + "b".repeat(5_000)
        val context = mainChatContext(listOf(user(text)), null, maxMessageChars = 1_000)!!
        assertTrue(context.text.length < 1_100)
        assertTrue(context.text.contains("a[…]") || context.text.contains("a\n[…]\nb"))
        assertTrue(context.text.endsWith("b"))
    }

    @Test
    fun requestBodySendsContextOnlyInTheSystemMessage() {
        val context = mainChatContext(listOf(user("main question"), assistant("main answer")), null)
        val body = sideRequestBody(SideTarget.openRouter("sk-or-secret", "anthropic/claude"),
            listOf(user("side question")), context)
        assertEquals("anthropic/claude", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.has("stream_options"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        val system = messages.getJSONObject(0)
        assertEquals("system", system.getString("role"))
        assertTrue(system.getString("content").contains("<main_chat>\nUser: main question\n\nAssistant: main answer\n</main_chat>"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("side question", messages.getJSONObject(1).getString("content"))
        assertFalse(body.toString().contains("sk-or-secret"))
    }

    @Test
    fun requestBodyWithoutContextHasNoMainChat() {
        val body = sideRequestBody(SideTarget.openAi("sk-openai", "gpt-4o"),
            listOf(user("one"), assistant("two"), user("three")), null)
        val messages = body.getJSONArray("messages")
        assertEquals(SIDE_SYSTEM_PROMPT, messages.getJSONObject(0).getString("content"))
        assertEquals(listOf("system", "user", "assistant", "user"),
            (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    @Test
    fun targetsSendEachKeyToItsOwnHost() {
        assertEquals(SIDE_OPENROUTER_URL, SideTarget.openRouter("k", "m").url)
        assertTrue(SIDE_OPENROUTER_URL.startsWith("https://openrouter.ai/"))
        assertEquals(SIDE_OPENAI_URL, SideTarget.openAi("k", "m").url)
        assertTrue(SIDE_OPENAI_URL.startsWith("https://api.openai.com/"))
    }

    @Test
    fun streamReaderJoinsDeltasAndReadsUsage() {
        val seen = mutableListOf<String>()
        val reader = SideStreamReader { seen += it }
        listOf(
            ": OPENROUTER PROCESSING",
            "",
            """data: {"model":"anthropic/claude-x","choices":[{"delta":{"role":"assistant","content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"content":"lo"}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15,"cost":0.0012,"prompt_tokens_details":{"cached_tokens":4}}}""",
            "data: [DONE]",
            """data: {"choices":[{"delta":{"content":"ignored"}}]}""",
        ).forEach(reader::line)
        assertTrue(reader.done)
        assertEquals(listOf("Hel", "Hello"), seen)
        val reply = reader.result()
        assertEquals("Hello", reply.text)
        assertEquals("anthropic/claude-x", reply.model)
        assertEquals(TokenUsage(12, 3, 15, 0.0012, cachedTokens = 4), reply.usage)
    }

    @Test
    fun streamReaderThrowsOnAnErrorChunk() {
        val reader = SideStreamReader()
        try {
            reader.line("""data: {"error":{"code":502,"message":"Provider went away"},"choices":[{"delta":{"content":""},"finish_reason":"error"}]}""")
            fail("expected an error")
        } catch (e: IOException) {
            assertEquals("Provider went away", e.message)
        }
    }

    @Test
    fun emptyStreamIsAnError() {
        val reader = SideStreamReader()
        reader.line("data: [DONE]")
        try {
            reader.result()
            fail("expected an error")
        } catch (e: IOException) {
            assertEquals("The model returned an empty reply.", e.message)
        }
    }

    @Test
    fun clientStreamsFromOpenRouterWithAttribution() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"Side \"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n" +
                    "data: [DONE]\n\n"))
            val target = SideTarget(server.url("/api/v1/chat/completions").toString(), "sk-or-1", "m", openRouter = true)
            val live = mutableListOf<String>()
            val reply = SideConversationClient().stream(target, listOf(user("hi")), null) { live += it }
            assertEquals("Side answer", reply.text)
            assertEquals(listOf("Side ", "Side answer"), live)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("Bearer sk-or-1", request.getHeader("Authorization"))
            assertEquals(OpenRouterAttribution.REFERER, request.getHeader("HTTP-Referer"))
            assertEquals(OpenRouterAttribution.TITLE, request.getHeader("X-OpenRouter-Title"))
            assertEquals("m", JSONObject(request.body.readUtf8()).getString("model"))
        }
    }

    @Test
    fun clientSendsNoOpenRouterHeadersToOpenAi() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"))
            val target = SideTarget(server.url("/v1/chat/completions").toString(), "sk-openai", "gpt-4o", openRouter = false)
            SideConversationClient().stream(target, listOf(user("hi")), null) {}
            val request = server.takeRequest()
            assertEquals("Bearer sk-openai", request.getHeader("Authorization"))
            assertNull(request.getHeader("HTTP-Referer"))
            assertNull(request.getHeader("X-OpenRouter-Title"))
        }
    }

    @Test
    fun clientErrorNamesTheProviderAndHidesTheKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Bad key sk-or-leak"}}"""))
            val target = SideTarget(server.url("/x").toString(), "sk-or-leak", "m", openRouter = true)
            try {
                SideConversationClient().stream(target, listOf(user("hi")), null) {}
                fail("expected an error")
            } catch (e: SideRequestException) {
                assertEquals("OpenRouter HTTP 401. Bad key [API key]", e.message)
            }
        }
    }

    @Test
    fun clientNeedsAKey() = runBlocking {
        try {
            SideConversationClient().stream(SideTarget.openAi(" ", "gpt-4o"), listOf(user("hi")), null) {}
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertEquals("Add your OpenAI API key in Settings first.", e.message)
        }
    }

    @Test
    fun cancellingStopsTheRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"slow\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.KEEP_OPEN).throttleBody(1, 10, TimeUnit.SECONDS))
            val target = SideTarget(server.url("/x").toString(), "k", "m", openRouter = true)
            val started = CompletableDeferred<Unit>()
            val call = async {
                started.complete(Unit)
                SideConversationClient().stream(target, listOf(user("hi")), null) {}
            }
            started.await()
            server.takeRequest(5, TimeUnit.SECONDS)
            withTimeout(5_000) {
                call.cancel()
                try {
                    call.await()
                    fail("expected cancellation")
                } catch (_: kotlinx.coroutines.CancellationException) {
                }
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SideConversationModelTest {
    private val openRouter = SideTarget.openRouter("k", "m")

    private class FakeStream {
        val calls = mutableListOf<Triple<SideTarget, List<SideTextMessage>, MainChatContext?>>()
        var reply: (suspend ((String) -> Unit) -> SideReply) = { live -> live("Hi"); SideReply("Hi there", "m", null) }
        val streamer: SideStreamer = { target, history, context, live ->
            calls += Triple(target, history, context)
            reply(live)
        }
    }

    @Test
    fun sendAddsBothMessagesAndSendsHistory() {
        val scope = TestScope(StandardTestDispatcher())
        val fake = FakeStream()
        val side = SideConversationModel(scope, fake.streamer, clock = { 0L })
        assertTrue(side.includeMainContext)
        val context = mainChatContext(listOf(SideTextMessage(true, "main")), null)
        side.prompt = "  first  "
        side.send(openRouter, context, null)
        assertEquals("first", side.pendingPrompt)
        assertEquals("", side.prompt)
        scope.advanceUntilIdle()
        assertNull(side.pendingPrompt)
        assertEquals(listOf(true to "first", false to "Hi there"), side.messages.map { it.isUser to it.text })
        assertEquals(context, fake.calls.single().third)
        assertTrue(side.unread) // the panel was closed

        side.open()
        assertFalse(side.unread)
        side.prompt = "second"
        side.send(openRouter, null, null)
        scope.advanceUntilIdle()
        assertEquals(listOf(true to "first", false to "Hi there", true to "second"),
            fake.calls[1].second.map { it.fromUser to it.text })
        assertNull(fake.calls[1].third)
        assertFalse(side.unread)
    }

    @Test
    fun failureShowsAnErrorCardThatIsNotSentAgain() {
        val scope = TestScope(StandardTestDispatcher())
        val fake = FakeStream()
        fake.reply = { throw SideRequestException("OpenRouter HTTP 429. Slow down") }
        val side = SideConversationModel(scope, fake.streamer, clock = { 0L })
        side.prompt = "one"
        side.send(openRouter, null, null)
        scope.advanceUntilIdle()
        assertTrue(side.messages.last().isError)
        assertEquals("OpenRouter HTTP 429. Slow down", side.messages.last().text)
        fake.reply = { SideReply("fine", null, null) }
        side.prompt = "two"
        side.send(openRouter, null, null)
        scope.advanceUntilIdle()
        assertEquals(listOf("one", "two"), fake.calls[1].second.map { it.text })
    }

    @Test
    fun typedTextIsSentWithoutTouchingTheSideDraft() {
        val scope = TestScope(StandardTestDispatcher())
        val fake = FakeStream()
        val side = SideConversationModel(scope, fake.streamer, clock = { 0L })
        side.prompt = "draft"
        side.send(openRouter, null, null, typed = "  from /btw  ")
        assertEquals("draft", side.prompt)
        assertEquals("from /btw", side.pendingPrompt)
        scope.advanceUntilIdle()
        assertEquals(listOf("from /btw", "Hi there"), side.messages.map { it.text })
        assertEquals("draft", side.prompt)
    }

    @Test
    fun typedTextWaitsInTheSideComposerWhileAReplyRuns() {
        val scope = TestScope(StandardTestDispatcher())
        val fake = FakeStream()
        fake.reply = { awaitCancellation() }
        val side = SideConversationModel(scope, fake.streamer, clock = { 0L })
        side.prompt = "first"
        side.send(openRouter, null, null)
        scope.advanceUntilIdle()
        side.send(openRouter, null, null, typed = "second")
        assertEquals(1, fake.calls.size)
        assertEquals("second", side.prompt)
        side.keepPrompt("third")
        assertEquals("second\nthird", side.prompt)
        side.stop()
        scope.advanceUntilIdle()
    }

    @Test
    fun stopKeepsThePartialReply() {
        val scope = TestScope(StandardTestDispatcher())
        val fake = FakeStream()
        fake.reply = { live -> live("Partial"); awaitCancellation() }
        val side = SideConversationModel(scope, fake.streamer, clock = { 0L })
        side.prompt = "long one"
        side.send(openRouter, null, null)
        scope.advanceUntilIdle()
        assertEquals("Partial", side.liveText)
        assertTrue(side.isRunning)
        side.stop()
        scope.advanceUntilIdle()
        assertFalse(side.isRunning)
        assertEquals(listOf("long one", "Partial"), side.messages.map { it.text })
        assertNotNull(side.messages.last().notice)
    }

    @Test
    fun newSideConversationClearsEverythingAndTurnsContextBackOn() {
        val scope = TestScope(StandardTestDispatcher())
        val fake = FakeStream()
        fake.reply = { awaitCancellation() }
        val side = SideConversationModel(scope, fake.streamer, clock = { 0L })
        side.includeMainContext = false
        side.prompt = "pending"
        side.send(openRouter, null, null)
        scope.advanceUntilIdle()
        side.startNew()
        scope.advanceUntilIdle()
        assertTrue(side.messages.isEmpty())
        assertFalse(side.isRunning)
        assertTrue(side.includeMainContext)
    }

    @Test
    fun moderationBlocksAPromptAndHidesLiveText() {
        val scope = TestScope(StandardTestDispatcher())
        val fake = FakeStream()
        val side = SideConversationModel(scope, fake.streamer, clock = { 0L })
        val blockAll = object : TextModerator { override suspend fun isSafe(text: String) = false }
        side.prompt = "bad"
        side.send(openRouter, null, blockAll)
        scope.advanceUntilIdle()
        assertEquals(POLICY_PROMPT_BLOCKED, side.error)
        assertEquals("bad", side.prompt)
        assertTrue(fake.calls.isEmpty())
        assertTrue(side.messages.isEmpty())

        val blockReplies = object : TextModerator { override suspend fun isSafe(text: String) = text != "Hi there" }
        side.send(openRouter, null, blockReplies)
        scope.advanceUntilIdle()
        assertTrue(side.messages.last().isModerated)
        assertEquals(POLICY_RESPONSE_WARNING, side.messages.last().text)
    }

    @Test
    fun mainChatEntriesBecomeContextWithoutErrorsOrModeratedReplies() {
        assertEquals(SideTextMessage(true, "look\n[Attached: photo.jpg]"),
            ChatEntry(isUser = true, text = "look", media = listOf(ChatMedia("f", "image", "image/jpeg", "photo.jpg")))
                .asSideContext())
        assertNull(ChatEntry(isUser = false, text = "boom", isError = true).asSideContext())
        assertNull(ChatEntry(isUser = false, text = "hidden", isModerated = true).asSideContext())
    }
}
