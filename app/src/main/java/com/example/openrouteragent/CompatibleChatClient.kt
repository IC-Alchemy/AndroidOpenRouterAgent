package com.example.openrouteragent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class CompatibleChatClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build(),
    private val base: (CustomProvider) -> HttpUrl = {
        requireNotNull(normalizeChatBase(it.baseUrl)) { "Enter a valid provider API URL." }
    },
) {
    // The image API can take four minutes before sending a response.
    private val imageHttp by lazy {
        http.newBuilder().readTimeout(5, TimeUnit.MINUTES).callTimeout(6, TimeUnit.MINUTES).build()
    }

    suspend fun models(profile: CustomProvider): List<CatalogModel> {
        val url = base(profile).newBuilder().addPathSegment("models").apply {
            if (profile.id == OPENUNCENSORED_ID) addQueryParameter("available", "true")
        }.build()
        return execute(profile, request(profile, url).build()) { response ->
            val data = JSONObject(readBody(response)).getJSONArray("data")
            val selected = JSONArray()
            for (i in 0 until data.length()) {
                val entry = data.optJSONObject(i) ?: continue
                if (entry.optString("modality").let { it.isNotBlank() && it != "text" }) continue
                if (profile.id == OPENUNCENSORED_ID && !entry.optBoolean("available", true)) continue
                selected.put(entry)
            }
            parseModelCatalog(JSONObject().put("data", selected).toString())
        }
    }

    suspend fun imageModels(profile: CustomProvider): List<OpenUncensoredImageModel> {
        require(profile.id == OPENUNCENSORED_ID)
        val url = base(profile).newBuilder().addPathSegments("images/models").build()
        val request = Request.Builder().url(url).build()
        return execute(profile, request) { response ->
            parseImageModels(JSONObject(readBody(response)).getJSONArray("data"))
        }
    }

    suspend fun generateImages(profile: CustomProvider, settings: ImageGenerationRequest): ImageGenerationResult {
        require(profile.id == OPENUNCENSORED_ID)
        require(profile.canChat) { "Enter the OpenUncensored API key first." }
        val url = base(profile).newBuilder().addPathSegments("images/generations").build()
        val request = request(profile, url).post(settings.payload().toString().toRequestBody(JSON_MEDIA)).build()
        return execute(profile, request, imageHttp) { response ->
            parseGeneratedImages(JSONObject(readBody(response, maxBytes = 32_000_000)), settings.count)
        }
    }

    suspend fun run(
        profile: CustomProvider, config: AgentConfig, prior: Workspace, prompt: String, model: String,
        onUpdates: (List<RunUpdate>) -> Unit,
    ): AgentRunResult = coroutineScope {
        val updates = Channel<RunUpdate>(Channel.UNLIMITED)
        val delivery = launch {
            for (first in updates) {
                val batch = mutableListOf(first)
                while (batch.size < 128) batch.add(updates.tryReceive().getOrNull() ?: break)
                onUpdates(batch)
            }
        }
        val result = try {
            val url = base(profile).newBuilder().addPathSegments("chat/completions").build()
            val payload = chatPayload(profile, config, prior, prompt, model)
            val request = request(profile, url).post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("Accept", if (profile.stream) "text/event-stream, application/json" else "application/json").build()
            updates.trySend(RunUpdate.Phase("connecting", null, 1))
            Result.success(execute(profile, request) { response ->
                val parser = ChatStream(model) { updates.trySend(it) }
                updates.trySend(RunUpdate.Phase("waiting", model, 1))
                if (response.header("Content-Type").orEmpty().contains("event-stream", ignoreCase = true)) {
                    val source = response.body?.source() ?: throw IOException("The provider returned an empty stream.")
                    parser.read(source)
                } else {
                    parser.json(JSONObject(readBody(response)), streamed = false)
                }
                parser.result()
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            updates.close()
        }
        delivery.join()
        result.getOrThrow()
    }

    private fun request(profile: CustomProvider, url: HttpUrl): Request.Builder = Request.Builder().url(url).apply {
        if (profile.apiKey.isNotBlank()) header("Authorization", "Bearer ${profile.apiKey.trim()}")
    }

    private suspend fun <T> execute(
        profile: CustomProvider, request: Request, client: OkHttpClient = http, handle: (Response) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException(
                        if (e is java.net.SocketTimeoutException) "${profile.name} timed out."
                        else "Could not connect to ${profile.name}. Check the API URL and network.", e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        val result = runCatching {
                            if (!response.isSuccessful) {
                                val raw = response.peekBody(8192).string()
                                val detail = runCatching { providerError(JSONObject(raw)) }.getOrNull()
                                throw IOException("${profile.name} HTTP ${response.code}. ${detail ?: response.message}")
                            }
                            handle(response)
                        }.recoverCatching { e ->
                            throw IOException(providerMessage(profile, e), e)
                        }
                        continuation.resumeWith(result)
                    }
                }
            })
        }

    private fun readBody(response: Response, maxBytes: Long = 2_000_000): String {
        val source = response.body?.source() ?: throw IOException("The provider returned an empty response.")
        if (source.request(maxBytes + 1)) throw IOException("The provider response exceeded ${maxBytes / 1_000_000} MB.")
        return source.readUtf8()
    }
}

internal fun chatPayload(profile: CustomProvider, config: AgentConfig, prior: Workspace, prompt: String, model: String): JSONObject {
    val result = JSONObject(profile.requestJson.ifBlank { "{}" })
    if (profile.id == OPENUNCENSORED_ID && profile.strategy.isNotBlank()) {
        val routing = result.optJSONObject("routing") ?: JSONObject()
        result.put("routing", routing.put("strategy", profile.strategy))
    }

    val system = buildList {
        if (profile.systemInstructions.isNotBlank()) add(profile.systemInstructions)
        config.savedSkills.filter { it.enabled && it.pinned }.forEach { add("## ${it.name}\n${it.body}") }
    }.joinToString("\n\n")
    val messages = JSONArray()
    if (system.isNotBlank()) messages.put(message("system", system))
    prior.messages.filter { !it.isError && it.text.isNotBlank() }.forEach {
        messages.put(message(if (it.isUser) "user" else "assistant", it.text))
    }
    messages.put(message("user", prompt))
    return result.put("model", model).put("messages", messages).put("stream", profile.stream)
}

private fun message(role: String, text: String): JSONObject = JSONObject().put("role", role).put("content", text)
private val JSON_MEDIA = "application/json".toMediaType()

private class ChatStream(
    private var model: String,
    private val publish: (RunUpdate) -> Unit,
) {
    private val text = StringBuilder()
    private var usage: TokenUsage? = null
    private var done = false
    private var finishReason: String? = null

    fun read(source: BufferedSource) {
        val frame = StringBuilder()
        var eventType = ""
        while (!done && !source.exhausted()) {
            val line = try {
                source.readUtf8LineStrict(1_000_000)
            } catch (_: EOFException) {
                if (source.buffer.size > 1_000_000) throw IOException("A provider stream event was too large.")
                source.readUtf8()
            }
            when {
                line.isBlank() -> {
                    dispatchFrame(frame, eventType)
                    frame.clear()
                    eventType = ""
                }
                line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (frame.isNotEmpty()) frame.append('\n')
                    frame.append(line.removePrefix("data:").removePrefix(" "))
                    if (frame.length > 1_000_000) throw IOException("A provider stream event was too large.")
                }
            }
        }
        if (!done) dispatchFrame(frame, eventType)
        if (!done && finishReason == null) throw IOException("The connection closed before the reply finished.")
    }

    private fun dispatchFrame(data: StringBuilder, kind: String) {
        val value = data.toString().trim()
        if (value.isEmpty()) return
        if (value == "[DONE]") {
            done = true
            return
        }
        if (kind == "error" && !value.startsWith("{")) throw IOException(value)
        val payload = JSONObject(value)
        if (kind == "error") throw IOException(providerError(payload) ?: "The provider interrupted the stream.")
        json(payload, streamed = true)
    }

    fun json(payload: JSONObject, streamed: Boolean) {
        providerError(payload)?.let { throw IOException(it) }
        (payload.opt("model") as? String)?.takeIf { it.isNotBlank() && it != model }?.let {
            model = it
            publish(RunUpdate.Phase("waiting", model, 1))
        }
        val choices = payload.optJSONArray("choices")
        val choice = choices?.optJSONObject(0)
        val part = choice?.optJSONObject(if (streamed) "delta" else "message")
        val snippet = contentText(part?.opt("content")).ifEmpty { contentText(part?.opt("refusal")) }
        if (snippet.isNotEmpty()) {
            text.append(snippet)
            if (text.length > 2_000_000) throw IOException("The reply exceeded 2 million characters.")
            publish(RunUpdate.Text(snippet, summary = false))
        }
        (choice?.opt("finish_reason") as? String)?.let { finishReason = it }
        val amount = payload.optJSONObject("ou")?.optDouble("cost_usd")?.takeIf(Double::isFinite)
        val previousUsage = usage
        payload.optJSONObject("usage")?.let {
            val input = it.optInt("prompt_tokens")
            val output = it.optInt("completion_tokens")
            usage = TokenUsage(input, output, it.optInt("total_tokens", input + output), amount,
                it.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens") ?: 0)
        }
        if (amount != null) usage = usage?.copy(cost = amount)
        if (usage != previousUsage) usage?.let { publish(RunUpdate.Usage(it)) }
    }

    fun result(): AgentRunResult {
        if (text.isBlank()) throw IOException(
            if (finishReason == "tool_calls") "The model requested a tool. Choose an OpenRouter model for agent tools."
            else "The provider returned no chat text.")
        return AgentRunResult(assistantText = text.toString(), reasoningSummary = null, shellSteps = emptyList(),
            searchQueries = emptyList(), citations = emptyList(), discoveredContainerId = null, usage = usage,
            modelUsed = model, notice = if (finishReason == "length") "The reply reached the provider’s output limit." else null)
    }
}

private fun contentText(value: Any?): String = when (value) {
    is String -> value
    is JSONArray -> buildString {
        for (i in 0 until value.length()) {
            val part = value.optJSONObject(i) ?: continue
            if (part.optString("type") in setOf("text", "output_text")) append(part.optString("text"))
        }
    }
    else -> ""
}

internal fun providerError(payload: JSONObject): String? = when (val error = payload.opt("error")) {
    is JSONObject -> error.optString("message").ifBlank { "The provider returned an error." }
    is String -> error.takeIf(String::isNotBlank)
    else -> (payload.opt("message") as? String)?.takeIf { payload.optJSONArray("choices") == null }
}

internal fun providerMessage(profile: CustomProvider, error: Throwable): String {
    val detail = when (error) {
        is org.json.JSONException -> "${profile.name} returned an invalid JSON response."
        is java.net.SocketTimeoutException -> "${profile.name} timed out."
        else -> error.message ?: "The ${profile.name} request failed."
    }
    return (if (profile.apiKey.isBlank()) detail else detail.replace(profile.apiKey, "[API key]")).take(450)
}
