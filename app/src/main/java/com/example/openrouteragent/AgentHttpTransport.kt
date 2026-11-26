package com.example.openrouteragent

import android.util.Base64
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

internal const val DEFAULT_STREAM_IDLE_SECONDS = 90L

/**
 * Said in a dropped-stream error when the response had only streamed reasoning and text. Nothing ran on
 * OpenRouter's side, so the app may send the request again. [RunRecovery] looks for this exact phrase.
 */
internal const val STREAM_DROP_SAFE_TO_RESEND = "No hosted tool had started in this response"

/** Output item types that only carry the model's own words. Any other item type is a tool call. */
private val wordsOnlyItems = setOf("reasoning", "message")
private val jsonType = "application/json; charset=utf-8".toMediaType()
private val retryableStatuses = setOf(408, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524)
private val requestIdHeaders = listOf("x-request-id", "x-generation-id", "openrouter-request-id", "cf-ray")

internal enum class AgentEndpoint { Responses, ChatCompletions }

/** Where an agent request goes: OpenRouter, or OpenAI directly with the user's own OpenAI key. */
internal enum class AgentTarget(val label: String) { OpenRouter("OpenRouter"), OpenAi("OpenAI") }

/** Timeouts and retry limits for one OpenRouter Responses stream. */
internal data class StreamPolicy(
    val connectTimeoutSeconds: Long = 10,
    /** Longest gap between any two received bytes, SSE keepalive comments included. */
    val idleTimeoutSeconds: Long = DEFAULT_STREAM_IDLE_SECONDS,
    val maxRetries: Int = 3,
    val baseDelayMillis: Long = 1_000,
    val maxDelayMillis: Long = 20_000,
    /** A longer Retry-After is reported to the user instead of silently waiting. */
    val maxRetryAfterMillis: Long = 60_000,
)

/**
 * Exponential backoff with full jitter, or the server's Retry-After when present.
 * Returns null when the server asks for a wait longer than the policy allows.
 */
internal fun retryDelayMillis(
    retry: Int, retryAfter: String?, policy: StreamPolicy, random: () -> Double = Math::random,
): Long? {
    val requested = retryAfter?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
        value.toLongOrNull()?.times(1_000) ?: runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
                System.currentTimeMillis()
        }.getOrNull()?.coerceAtLeast(0)
    }
    if (requested != null) return requested.takeIf { it <= policy.maxRetryAfterMillis }
    val ceiling = min(policy.maxDelayMillis.toDouble(), policy.baseDelayMillis * 2.0.pow(retry))
    return (ceiling * random()).toLong()
}

/**
 * A pull-based byte transport. The SDK still owns decoding events and orchestrating turns.
 *
 * Failures before response headers (connection errors, 408/429/5xx) are retried here, because no
 * response bytes reached the SDK and OpenRouter has not started streaming. Failures after the
 * headers are never retried here, because the SDK has already read part of the stream. The error
 * says whether a hosted tool had started, so the run can decide whether to send the turn again.
 */
internal class AgentHttpTransport(
    private val endpoint: HttpUrl = "https://openrouter.ai/api/v1/responses".toHttpUrl(),
    /** OpenAI's Responses endpoint, for runs with OpenAI (direct) models. Only the OpenAI key is sent here. */
    private val openAiEndpoint: HttpUrl = OPENAI_RESPONSES_URL.toHttpUrl(),
    private val policy: StreamPolicy = StreamPolicy(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(policy.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(policy.idleTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
    private val encode: (ByteArray, Int) -> String = { buffer, size ->
        Base64.encodeToString(buffer, 0, size, Base64.NO_WRAP)
    },
    /** Receives one diagnostic line per state change. Never passed the API key or request body. */
    private val log: (String) -> Unit = { runCatching { Log.i("AgentHttp", it) } },
    private val random: () -> Double = Math::random,
    /** What the phone was doing when a stream dropped (screen, app in front, network changes), or null. */
    private val environment: () -> String? = { RunEnvironment.describe() },
) {
    private data class Key(val run: String, val request: String)
    private class Exchange(
        val key: Key, val request: Request, val client: OkHttpClient, val diagnostics: StreamDiagnostics,
        val route: AgentEndpoint,
        val target: AgentTarget,
        val emit: (String, Any) -> Unit,
    ) {
        @Volatile var call: Call? = null
        @Volatile var response: Response? = null
        @Volatile var pendingRetry: ScheduledFuture<*>? = null
        val reading = AtomicBoolean()
    }

    private val active = ConcurrentHashMap<Key, Exchange>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "agent-http-retry").apply { isDaemon = true }
    }

    fun start(
        runId: String, requestId: String, apiKey: String, body: String,
        model: String? = null, idleSeconds: Long? = null, route: AgentEndpoint = AgentEndpoint.Responses,
        target: AgentTarget = AgentTarget.OpenRouter,
        emit: (String, Any) -> Unit,
    ) {
        val key = Key(runId, requestId)
        val idle = (idleSeconds ?: policy.idleTimeoutSeconds).coerceAtLeast(1)
        // OpenAI runs only use the Responses API: the runtime's Chat Completions calls are OpenRouter features.
        require(target == AgentTarget.OpenRouter || route == AgentEndpoint.Responses)
        val url = when {
            target == AgentTarget.OpenAi -> openAiEndpoint
            route == AgentEndpoint.Responses -> endpoint
            else -> endpoint.newBuilder().encodedPath("/api/v1/chat/completions").build()
        }
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $apiKey")
            .apply { if (target == AgentTarget.OpenRouter) openRouterAttribution() }
            .header("Accept", if (route == AgentEndpoint.Responses) "text/event-stream, application/json" else "application/json")
            .post(body.toRequestBody(jsonType))
            .build()
        val perRequest = if (idle == policy.idleTimeoutSeconds) client
            else client.newBuilder().readTimeout(idle, TimeUnit.SECONDS).build()
        val exchange = Exchange(key, request, perRequest, StreamDiagnostics("$runId/$requestId", model, idle), route, target, emit)
        if (active.putIfAbsent(key, exchange) != null) return
        attempt(exchange)
    }

    private fun attempt(exchange: Exchange) {
        if (active[exchange.key] !== exchange) return
        val diagnostics = exchange.diagnostics
        diagnostics.newAttempt()
        log("request ${diagnostics.describeStart()}")
        val call = exchange.client.newCall(exchange.request)
        exchange.call = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled() || active[exchange.key] !== exchange) return
                if (e !is javax.net.ssl.SSLException && retry(exchange, e.describe(), null)) return
                finish(exchange, "error", failure(exchange, e, beforeHeaders = true))
            }

            override fun onResponse(call: Call, response: Response) {
                if (active[exchange.key] !== exchange) {
                    response.close()
                    return
                }
                diagnostics.headers(response)
                log("headers ${diagnostics.describe()}")
                if (!response.isSuccessful) {
                    val detail = response.use { errorDetail(it) }
                    if (response.code in retryableStatuses &&
                        retry(exchange, "HTTP ${response.code}", response.header("Retry-After"), detail)) return
                    finish(exchange, "error", httpFailure(exchange, response.code, response.header("Retry-After"), detail))
                    return
                }
                exchange.response = response
                exchange.emit("headers", JSONObject()
                    .put("status", response.code)
                    .put("contentType", response.header("Content-Type") ?: "application/octet-stream")
                    .put("retryAfter", response.header("Retry-After")))
            }
        })
    }

    /** Schedules another attempt and tells the UI why. Returns false when retries are exhausted. */
    private fun retry(exchange: Exchange, reason: String, retryAfter: String?, detail: String? = null): Boolean {
        // A non-streaming completion may have been billed before the connection failed.
        if (exchange.route == AgentEndpoint.ChatCompletions) return false
        val diagnostics = exchange.diagnostics
        diagnostics.lastFailure = listOfNotNull(reason, detail).joinToString(": ")
        val retry = diagnostics.retries
        if (retry >= policy.maxRetries) return false
        val delay = retryDelayMillis(retry, retryAfter, policy, random) ?: return false
        diagnostics.retries++
        val notice = "$reason · retry ${retry + 1} of ${policy.maxRetries} in ${seconds(delay)}"
        log("retry ${diagnostics.requestId} $notice")
        exchange.emit("retry", notice)
        exchange.pendingRetry = scheduler.schedule({ attempt(exchange) }, delay, TimeUnit.MILLISECONDS)
        return true
    }

    fun read(runId: String, requestId: String) {
        val key = Key(runId, requestId)
        val exchange = active[key] ?: return
        val response = exchange.response ?: return
        if (!exchange.reading.compareAndSet(false, true)) return
        client.dispatcher.executorService.execute {
            try {
                val bytes = ByteArray(8192)
                val count = response.body?.source()?.read(bytes) ?: -1
                exchange.reading.set(false)
                if (count < 0) {
                    log("end ${exchange.diagnostics.describe()}")
                    finish(exchange, "end", JSONObject.NULL)
                } else {
                    exchange.diagnostics.received(bytes, count)
                    if (active[key] === exchange) exchange.emit("chunk", encode(bytes, count))
                }
            } catch (e: IOException) {
                exchange.reading.set(false)
                if (active[key] !== exchange) return@execute
                finish(exchange, "error", failure(exchange, e, beforeHeaders = false))
            }
        }
    }

    fun cancel(runId: String, requestId: String) {
        val exchange = active.remove(Key(runId, requestId)) ?: return
        log("cancel ${exchange.diagnostics.describe()}")
        exchange.pendingRetry?.cancel(false)
        exchange.call?.cancel()
        client.dispatcher.executorService.execute { exchange.response?.close() }
    }

    fun cancelRun(runId: String) {
        active.keys.filter { it.run == runId }.forEach { cancel(it.run, it.request) }
    }

    private fun finish(exchange: Exchange, kind: String, value: Any) {
        if (!active.remove(exchange.key, exchange)) return
        exchange.response?.close()
        exchange.emit(kind, value)
    }

    private fun failure(exchange: Exchange, error: IOException, beforeHeaders: Boolean): String {
        val diagnostics = exchange.diagnostics
        val host = exchange.target.label
        val retried = if (diagnostics.retries > 0) " after ${diagnostics.retries} retries" else ""
        val message = when {
            error is SocketTimeoutException && beforeHeaders ->
                "$host did not answer within ${diagnostics.idleSeconds} s$retried."
            error is SocketTimeoutException ->
                "$host sent no data for ${diagnostics.idleSeconds} s, so the app stopped waiting. The model " +
                    "or a hosted tool may still be working. Retry, raise the stream idle timeout in SDK settings, " +
                    "or retry without tools."
            error is java.net.UnknownHostException ->
                "Android could not reach $host$retried. Check your network connection."
            error is javax.net.ssl.SSLException -> "Android could not establish a secure connection to $host."
            beforeHeaders -> "The $host connection failed$retried (${error.describe()})."
            diagnostics.toolStarted -> "The $host stream closed unexpectedly (${error.describe()}). A hosted " +
                "tool had started in this response, so it was not simply sent again."
            else -> "The $host stream closed unexpectedly (${error.describe()}). $STREAM_DROP_SAFE_TO_RESEND, " +
                "so it can be sent again."
        }
        val phone = if (beforeHeaders) null else environment()?.takeIf { it.isNotBlank() }
        val tag = if (error is SocketTimeoutException && !beforeHeaders) "idle-timeout" else "failure"
        log("$tag ${diagnostics.describe()} error=${error.describe()}${phone?.let { " phone=$it" }.orEmpty()}")
        return listOfNotNull(message, phone?.let { "Phone: $it." }, diagnostics.describe()).joinToString(" ")
    }

    private fun httpFailure(exchange: Exchange, code: Int, retryAfter: String?, detail: String?): String {
        val diagnostics = exchange.diagnostics
        val model = diagnostics.model ?: "the selected model"
        val advice = if (exchange.target == AgentTarget.OpenAi) openAiAdvice(code, model, retryAfter) else when (code) {
            400 -> "OpenRouter rejected the request. Check that $model is a valid model ID in Models and that " +
                "it supports the enabled tools."
            401 -> "OpenRouter rejected the API key. Enter a valid key in Settings."
            402 -> "Your OpenRouter account needs more credits for $model."
            403 -> "OpenRouter refused this request for the key or model, for example because of moderation or " +
                "key limits."
            404 -> "OpenRouter has no available endpoint for $model. Choose another model in Models."
            408 -> "OpenRouter timed out waiting for the request."
            413 -> "The conversation is too large for $model. Start a new chat or detach the sandbox."
            429 -> retryAfter?.let { "OpenRouter rate-limited this key and asked to wait ${it.trim()} s." }
                ?: "OpenRouter rate-limited this key. Wait a moment before retrying."
            in 500..599 -> "OpenRouter or the model provider failed."
            else -> "OpenRouter returned an unexpected status."
        }
        val retried = if (diagnostics.retries > 0) " Gave up after ${diagnostics.retries} retries." else ""
        log("http-error ${diagnostics.describe()}")
        return "${exchange.target.label} HTTP $code. $advice${detail?.let { " Detail: ${it.trimEnd('.')}." }.orEmpty()}$retried ${diagnostics.describe()}"
    }

    private fun openAiAdvice(code: Int, model: String, retryAfter: String?): String = when (code) {
        400 -> "OpenAI rejected the request. Check that $model supports the enabled tools, or turn some off in Settings."
        401 -> "OpenAI rejected the API key. Check the OpenAI key in Settings."
        403 -> "OpenAI refused this request for the key, project, model or region."
        404 -> "OpenAI has no model $model for this key. Choose another model in Models."
        408 -> "OpenAI timed out waiting for the request."
        413 -> "The conversation is too large for $model. Start a new chat."
        429 -> retryAfter?.let { "OpenAI rate-limited this key and asked to wait ${it.trim()} s." }
            ?: "OpenAI rate-limited this key, or the account is out of quota. Check billing on OpenAI."
        in 500..599 -> "OpenAI failed."
        else -> "OpenAI returned an unexpected status."
    }

    private fun errorDetail(response: Response): String? {
        val raw = runCatching { response.peekBody(8192).string() }.getOrNull() ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val message = json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json?.optString("message")?.takeIf { it.isNotBlank() }
            ?: raw.takeIf { it.isNotBlank() && !raw.trimStart().startsWith("<") }
        return message?.take(300)
    }

    private fun IOException.describe(): String =
        javaClass.simpleName + (message?.takeIf { it.isNotBlank() }?.let { ": ${it.take(160)}" }.orEmpty())
}

private fun seconds(millis: Long): String =
    if (millis < 1_000) "${millis} ms" else "${(millis + 500) / 1_000} s"

/** Counters and the last SSE event of one exchange, for idle and disconnect reports. */
internal class StreamDiagnostics(val requestId: String, val model: String?, val idleSeconds: Long) {
    private val started = System.nanoTime()
    @Volatile var retries = 0
    @Volatile var lastFailure: String? = null
    private var attempts = 0
    private var status: Int? = null
    private var providerRequestId: String? = null
    private var bytes = 0L
    private var keepalives = 0
    private var lastEvent: String? = null
    private var lastItemType: String? = null
    /** Whether any streamed output item was a tool call rather than reasoning or text. */
    @Volatile var toolStarted = false
        private set
    private var lastByteAt: Long? = null
    private val line = StringBuilder()

    @Synchronized fun newAttempt() {
        attempts++
        status = null
    }

    @Synchronized fun headers(response: Response) {
        status = response.code
        providerRequestId = requestIdHeaders.firstNotNullOfOrNull { response.header(it) } ?: providerRequestId
    }

    @Synchronized fun received(buffer: ByteArray, count: Int) {
        bytes += count
        lastByteAt = System.nanoTime()
        for (index in 0 until count) {
            val char = (buffer[index].toInt() and 0xff).toChar()
            if (char == '\n') {
                inspect(line)
                line.setLength(0)
            } else if (line.length < 600) {
                // Only the start of a line is needed to classify it, so long data lines stay bounded.
                line.append(char)
            }
        }
    }

    private fun inspect(text: CharSequence) {
        val value = text.trimEnd('\r')
        when {
            value.startsWith(":") -> keepalives++
            value.startsWith("event:") -> lastEvent = value.substring(6).trim()
            value.startsWith("data:") -> {
                TYPE.find(value)?.groupValues?.get(1)?.let {
                    if (lastEvent == null || it.startsWith("response.")) lastEvent = it
                    // Events such as response.web_search_call.searching or response.function_call_arguments.delta.
                    if (TOOL_EVENT.containsMatchIn(it)) toolStarted = true
                }
                ITEM_TYPE.find(value)?.groupValues?.get(1)?.let {
                    lastItemType = it
                    if (it !in wordsOnlyItems) toolStarted = true
                }
            }
        }
    }

    @Synchronized fun describeStart(): String =
        "id=$requestId model=${model ?: "?"} attempt=$attempts idle=${idleSeconds}s"

    /** One line with no secrets: safe for logcat and for the user-facing error. */
    @Synchronized fun describe(): String {
        val now = System.nanoTime()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(now - started)
        val sinceByte = lastByteAt?.let { TimeUnit.NANOSECONDS.toMillis(now - it) }
        return buildString {
            append("[request ").append(requestId)
            append(", model ").append(model ?: "unknown")
            status?.let { append(", HTTP ").append(it) }
            providerRequestId?.let { append(", request id ").append(it) }
            append(", attempt ").append(attempts)
            append(", ").append(bytes).append(" bytes in ").append(seconds(elapsed))
            sinceByte?.let { append(", last byte ").append(seconds(it)).append(" ago") }
            append(", last event ").append(lastEvent ?: "none")
            lastItemType?.let { append(" (").append(it).append(')') }
            append(", keepalives=").append(keepalives)
            lastFailure?.let { append(", previous failure ").append(it) }
            append(']')
        }
    }

    private companion object {
        val TYPE = Regex("^data:\\s*\\{\\s*\"type\"\\s*:\\s*\"([^\"]{1,80})\"")
        val TOOL_EVENT = Regex("""_call|tool|openrouter:|shell|image_generation|mcp_""")
        val ITEM_TYPE = Regex("\"item\"\\s*:\\s*\\{[^{}]*?\"type\"\\s*:\\s*\"([^\"]{1,80})\"")
    }
}
