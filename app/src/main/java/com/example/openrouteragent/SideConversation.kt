package com.example.openrouteragent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * Side conversation: a separate chat in a panel over the main chat. It has its own requests and history, so a main
 * chat run and a side request never wait for or cancel each other. With "Include main chat context" on, each side
 * request carries a read-only copy of the main chat; nothing from the side conversation is written to the main chat.
 *
 * Side requests are plain Chat Completions calls without tools, streamed: to OpenRouter with the OpenRouter key, or to
 * api.openai.com with the OpenAI key for "openai-direct/" models. Each key only goes to its own host.
 */

internal const val SIDE_OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
internal const val SIDE_OPENAI_URL = "https://api.openai.com/v1/chat/completions"

/** Most characters of main chat sent as context, newest messages first; older messages are left out. */
internal const val SIDE_CONTEXT_MAX_CHARS = 60_000
/** Most characters of one main chat message sent as context. Longer messages keep their start and end. */
internal const val SIDE_CONTEXT_MAX_MESSAGE_CHARS = 12_000

internal const val SIDE_SYSTEM_PROMPT = "This is a side conversation in OpenRouter Agent Studio. The user opened it " +
    "next to their main chat to ask something separately; the main chat keeps going on its own and nothing said here " +
    "is added to it. Answer the user's side messages directly."

/** One message of a conversation as plain text, for building side requests. */
internal data class SideTextMessage(val fromUser: Boolean, val text: String)

/** The main chat as context: the text to send, and how many messages it holds or leaves out. */
internal data class MainChatContext(val text: String, val included: Int, val omitted: Int)

/**
 * The main chat written out as a transcript, newest messages kept first when it is too long. [pendingPrompt] is a
 * main chat message still waiting for its reply. Returns null when there is nothing to share.
 */
internal fun mainChatContext(
    messages: List<SideTextMessage>,
    pendingPrompt: String? = null,
    maxChars: Int = SIDE_CONTEXT_MAX_CHARS,
    maxMessageChars: Int = SIDE_CONTEXT_MAX_MESSAGE_CHARS,
): MainChatContext? {
    val blocks = messages.mapNotNull { message ->
        val text = message.text.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        "${if (message.fromUser) "User" else "Assistant"}: ${clipMiddle(text, maxMessageChars)}"
    } + listOfNotNull(pendingPrompt?.trim()?.takeIf(String::isNotEmpty)?.let {
        "User (still waiting for the main chat's reply): ${clipMiddle(it, maxMessageChars)}"
    })
    if (blocks.isEmpty()) return null
    val kept = ArrayDeque<String>()
    var used = 0
    for (block in blocks.asReversed()) {
        val cost = block.length + 2
        if (kept.isNotEmpty() && used + cost > maxChars) break
        kept.addFirst(if (cost > maxChars) clipMiddle(block, maxChars - 2) else block)
        used += cost
    }
    val omitted = blocks.size - kept.size
    val transcript = buildString {
        if (omitted > 0) append("[").append(omitted).append(" earlier main chat messages left out]\n\n")
        append(kept.joinToString("\n\n"))
    }
    return MainChatContext(transcript, kept.size, omitted)
}

/** The system message: what the side conversation is and, when given, the main chat as read-only context. */
internal fun sideSystemPrompt(context: MainChatContext?): String = if (context == null) SIDE_SYSTEM_PROMPT else
    SIDE_SYSTEM_PROMPT + "\n\nFor context, here is the user's main chat so far. It is read-only reference: the " +
        "main chat continues separately, so don't reply to it or carry on its tasks unless the user asks you to " +
        "here.\n\n<main_chat>\n" + context.text + "\n</main_chat>"

/** Where a side request goes. [model] is the provider's own model ID. */
internal data class SideTarget(val url: String, val apiKey: String, val model: String, val openRouter: Boolean) {
    companion object {
        fun openRouter(apiKey: String, model: String) = SideTarget(SIDE_OPENROUTER_URL, apiKey, model, openRouter = true)
        fun openAi(apiKey: String, model: String) = SideTarget(SIDE_OPENAI_URL, apiKey, model, openRouter = false)
    }
}

/** The Chat Completions body for a side request: the system message, then the side conversation so far. */
internal fun sideRequestBody(target: SideTarget, history: List<SideTextMessage>, context: MainChatContext?): JSONObject {
    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", sideSystemPrompt(context)))
    history.filter { it.text.isNotBlank() }.forEach {
        messages.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text))
    }
    val body = JSONObject().put("model", target.model).put("stream", true).put("messages", messages)
    // OpenAI only adds usage to a stream when asked; OpenRouter always sends it in the last chunk.
    if (!target.openRouter) body.put("stream_options", JSONObject().put("include_usage", true))
    return body
}

/** What a finished side request gave back. */
internal data class SideReply(val text: String, val model: String?, val usage: TokenUsage?)

/**
 * Reads a Chat Completions stream line by line (server-sent events). Text deltas go to [onText] as they arrive;
 * an error sent inside the stream throws. Comment lines such as OpenRouter's keep-alives are skipped.
 */
internal class SideStreamReader(private val onText: (String) -> Unit = {}) {
    private val text = StringBuilder()
    private var model: String? = null
    private var usage: TokenUsage? = null
    var done = false
        private set

    fun line(raw: String) {
        val line = raw.trimEnd('\r')
        if (done || !line.startsWith("data:")) return
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) return
        if (data == "[DONE]") {
            done = true
            return
        }
        val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return
        sideStreamError(chunk)?.let { throw IOException(it) }
        chunk.optString("model").takeIf(String::isNotBlank)?.let { model = it }
        chunk.optJSONObject("usage")?.let { usage = parseSideUsage(it) ?: usage }
        val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        val content = delta?.opt("content")
        if (content is String && content.isNotEmpty()) {
            text.append(content)
            onText(text.toString())
        }
        if (choice.optString("finish_reason") == "error") throw IOException("The model stopped with an error.")
    }

    fun result(): SideReply {
        if (text.isBlank()) throw IOException("The model returned an empty reply.")
        return SideReply(text.toString(), model, usage)
    }
}

internal fun sideStreamError(payload: JSONObject): String? = when (val error = payload.opt("error")) {
    is JSONObject -> error.optString("message").ifBlank { "The provider returned an error." }
    is String -> error.takeIf(String::isNotBlank)
    else -> null
}

internal fun parseSideUsage(usage: JSONObject): TokenUsage? {
    if (!usage.has("prompt_tokens") && !usage.has("completion_tokens")) return null
    val input = usage.optInt("prompt_tokens")
    val output = usage.optInt("completion_tokens")
    return TokenUsage(
        inputTokens = input,
        outputTokens = output,
        totalTokens = usage.optInt("total_tokens", input + output),
        cost = if (usage.has("cost") && !usage.isNull("cost")) usage.optDouble("cost").takeUnless(Double::isNaN) else null,
        cachedTokens = usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens") ?: 0,
        reasoningTokens = usage.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens") ?: 0,
    )
}

/** Sends side requests. It shares nothing with the agent runtime, so it runs alongside a main chat run. */
internal class SideConversationClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(1, TimeUnit.MINUTES)
        .readTimeout(3, TimeUnit.MINUTES)
        .callTimeout(15, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build(),
) {
    /** Streams one reply. Cancelling the calling coroutine cancels the HTTP call. */
    suspend fun stream(
        target: SideTarget,
        history: List<SideTextMessage>,
        context: MainChatContext?,
        onText: (String) -> Unit,
    ): SideReply {
        val provider = if (target.openRouter) "OpenRouter" else "OpenAI"
        val key = target.apiKey.trim()
        require(key.isNotEmpty()) { "Add your $provider API key in Settings first." }
        val builder = Request.Builder().url(target.url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
        if (target.openRouter) builder.openRouterAttribution()
        val request = builder
            .post(sideRequestBody(target, history, context).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(request)
        return coroutineScope {
            // The read below blocks a thread, so cancelling the coroutine cancels the call to unblock it.
            // UNDISPATCHED so the watcher is waiting before anything can cancel it.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try { withContext(Dispatchers.IO) { read(call, provider, key, onText) } } finally { watcher.cancel() }
        }
    }

    private fun CoroutineScope.read(call: Call, provider: String, key: String, onText: (String) -> Unit): SideReply {
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = runCatching { sideStreamError(JSONObject(response.peekBody(8192).string())) }
                        .getOrNull() ?: response.message.ifBlank { "Request failed." }
                    throw SideRequestException("$provider HTTP ${response.code}. ${detail.withoutKey(key)}")
                }
                val source = response.body?.source() ?: throw SideRequestException("$provider returned an empty response.")
                val reader = SideStreamReader(onText)
                while (!reader.done) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    try {
                        reader.line(line)
                    } catch (e: IOException) {
                        throw SideRequestException((e.message ?: "$provider returned an error.").withoutKey(key))
                    }
                }
                try {
                    reader.result()
                } catch (e: IOException) {
                    throw SideRequestException(e.message ?: "$provider returned an empty reply.")
                }
            }
        } catch (e: SideRequestException) {
            throw e
        } catch (e: IOException) {
            coroutineContext.ensureActive() // a cancelled call surfaces as CancellationException, not an error
            throw SideRequestException(if (e is java.net.SocketTimeoutException) "$provider timed out."
                else "Could not reach $provider. Check your connection.", e)
        }
    }
}

/** A side request failed; the message is ready to show and holds no API key. */
internal class SideRequestException(message: String, cause: Throwable? = null) : IOException(message, cause)

private fun String.withoutKey(key: String): String = if (key.isBlank()) this else replace(key, "[API key]")

private fun clipMiddle(text: String, max: Int): String {
    if (text.length <= max) return text
    val marker = "\n[…]\n"
    val keep = (max - marker.length).coerceAtLeast(2)
    return text.take(keep / 2) + marker + text.takeLast(keep - keep / 2)
}
